# Команды, Resilience4j и композиция

## Границы модулей

| Модуль | Зависимости и ответственность |
| --- | --- |
| `proxy` | Servlet API, Netty, SLF4J; потоковая передача, framing, буферы, upstream pools, транспортный lifecycle |
| `command-support` | Reactor API, Resilience4j 2.4.0; TimeLimiter, Bulkhead, CircuitBreaker, opt-in Retry, отмена и подтверждённая очистка |
| `app` | Зависит от обоих модулей; связывает HTTP-запрос с командой, настраивает политики и переводит отказы в HTTP |

`proxy` не импортирует Resilience4j или типы команды. `command-support` не импортирует
proxy, Servlet либо Netty. Между библиотеками транспорта и команд нет Gradle-зависимости.
Spring Boot BOM согласует зависимости `command-support`; Spring и Micrometer интеграция
не требует Actuator и не добавляет HTTP management endpoints.

`ProxyFilter` выделяет `/proxy` и вызывает `ProxyRequestHandler`. Обработчик создаёт
`ProxyRequestCommand`, наследующую `AbstractResourceCommand<ProxyResult>`, и синхронно
подписывается на её `execute(): Mono<ProxyResult>`. Только допущенный `doStart()`
обращается к `ProxyEngine.forwardAsync(...)`. До возврата фильтра транспорт должен
получить владение Servlet async либо команда должна синхронно отклонить запрос.
Конструктор и создание Mono не создают соединений и не читают тело.

Полный API обычных и ресурсных команд: [command-support/README.md](command-support/README.md).

## Контракты и завершение

- `Command<T>.execute()` — холодный Mono; работа начинается только после подписки и admission.
- `CommandAction<T>.start()` / ресурсный `doStart()` — короткий неблокирующий запуск операции.
- Обычный `AbstractResilienceCommand.doExecute()` вызывается отложенно; синхронные ошибки попадают в fallback.
- `CommandTask<T>.completion()` — результат после **фактического освобождения ресурсов**.
- `CommandTask<T>.cancel(reason)` — неблокирующая кооперативная отмена.
- `CommandExecution<T>.result()` — результат защищённой команды; TimeLimiter может
  завершить его раньше завершения cleanup.
- `CommandExecution<T>.terminated()` — отдельный сигнал окончания cleanup и возврата
  bulkhead permit. Наблюдатель результата не должен считать timeout освобождением ресурсов.
- `CommandExecution<T>.cancel()` — явная отмена вызывающей стороной. Отмена копии
  `CompletableFuture`, полученной из readonly stage, не заменяет этот метод.

В `proxy` добавлен нейтральный транспортный API `ProxyCall`: `completion()` и
`cancel(httpStatus)`. Он не зависит от Command. Старый `ProxyEngine.forward()` сохранён.
`ProxyCall.completion()` завершается после полного тела и cleanup, включая pending
pool acquire, возврат/закрытие канала и освобождение transport admission.
HTTP-статус `ProxyResult` описывает исход обмена; после commit ошибка не меняет уже
отправленный статус, а прерывает тело.
Если HTTP-ответ уже завершён, а команда ещё ожидает cleanup, поздний TimeLimiter
может завершить `result()` таймаутом, но не способен изменить или повторно прервать
уже завершённый HTTP-ответ. `terminated()` по-прежнему ожидает освобождения ресурсов.

App-адаптер преобразует generic `TIMEOUT` в `ProxyCall.cancel(504)`, а явную отмену
или shutdown — в `cancel(503)`. Запись HTTP-ответа остаётся на owner loop прокси.
Scheduler Resilience4j напрямую к Servlet response не обращается.

## Порядок и смысл политик

Сначала проверяется Bulkhead без ожидания, затем разрешение CircuitBreaker.
TimeLimiter получает отдельный stage результата и запускается до ресурсного `doStart()`.
Если отказ произошёл до запуска, команда не обращается к proxy. Отмена во время
короткого `start()` запоминается и доставляется задаче после публикации её handle.

Слот Bulkhead возвращается только после `CommandTask.completion()`. Поэтому timeout
не создаёт бесконечную очередь ещё работающих сетевых операций. Неотменяемая зависшая
задача продолжит занимать слот: реализация CommandTask обязана завершать cleanup после cancel.

Используется semaphore Bulkhead, а не ThreadPoolBulkhead: Servlet/Netty I/O уже
асинхронный. Отдельный рабочий пул и дополнительная очередь для каждого запроса
не нужны. Декоратор использует общий scheduler для deadlines и разрешённых retry-пауз.

В Resilience4j 2.4.0 CompletionStage-вариант TimeLimiter завершает future исключением,
но сам не закрывает сокет и не вызывает `CommandTask.cancel()`. Поэтому отмена здесь
связана явно. Таймер также удаляется при любом исходе, включая раннее исключение;
очередь scheduler не должна удерживать завершённые запросы до исходного deadline.

## Классификация в app

| Исход | HTTP-поведение | CircuitBreaker |
| --- | --- | --- |
| Обычный upstream 2xx/3xx/4xx | Передаётся ответ и тело | Success |
| Upstream 5xx | Передаётся ответ и тело | Failure |
| Ошибка соединения/протокола upstream | 502 до commit, abort после commit | Failure |
| Подтверждённый TCP/DNS/TLS timeout | 504 до commit, abort после commit | Failure |
| Timeout финальных upstream headers после полного upload | 504 и cleanup | Failure |
| TimeLimiter или абсолютный transport deadline | Для активного обмена: 504 до commit, abort после commit | Ignore |
| Отключение клиента | Отмена и cleanup | Ignore |
| Отказ Processor, локальный admission, shutdown | Статус транспортного/прикладного отказа | Ignore |
| Bulkhead заполнен или CircuitBreaker OPEN | 503 без вызова proxy | Не является ошибкой upstream |

Ignore означает отсутствие sample, а не успешный запрос. Общий deadline streaming
зависит от upload клиента, размера тела и скорости скачивания. Такой timeout сам по
себе не доказывает отказ upstream. По этой же причине slow-call threshold установлен
выше полного бюджета команды. При необходимости другая политика задаётся в app.

`proxy.response-headers-timeout=10s` измеряет отдельную фазу: от успешной отправки
последнего request content до финальных upstream headers. Медленный клиентский upload
ещё не запускает этот таймер; получение финальных headers его отменяет. Промежуточные
1xx его не продлевают. Download/SSE body не попадает в эту фазу. Поэтому этот timeout
классифицируется как upstream failure, и повторные зависания backend открывают circuit.
Дополнительного body-idle timeout нет.

После wait duration CircuitBreaker переходит к HALF_OPEN по следующему запросу.
Успешные разрешённые probes закрывают его; отдельный поток для автоматического
перехода не включён. Автоматических retries и буферизации тела нет.

## Настройки

```yaml
proxy-command:
  timeout: 30s
  max-concurrent: 128
  failure-rate-threshold: 50
  sliding-window-size: 20
  minimum-number-of-calls: 10
  wait-duration-in-open-state: 10s
  permitted-calls-in-half-open-state: 3
```

Пример переопределения:

```bash
PROXY_UPSTREAM=http://localhost:9000 ./gradlew :app:bootRun \
  --args='--proxy-command.timeout=5s --proxy.response-headers-timeout=2s --proxy-command.max-concurrent=64'
```

По умолчанию одна именованная политика защищает общую логическую группу `/proxy`, включая
разрешённые Processor изменения origin. Если upstream требуют независимой изоляции,
создайте в app ограниченный набор именованных политик и выбирайте его при
композиции. Не создавайте CircuitBreaker на каждый произвольный URI или client ID.

`proxy-command.timeout` и `proxy.exchange-timeout` независимы. Первый — политика
команды; второй — резервный предел транспорта. Фактически сработает более ранний
deadline. Для больших файлов/SSE настройте оба; события не продлевают deadline.
Timeout headers должен помещаться в оставшийся общий бюджет, чтобы успеть дать
диагностируемый upstream failure. Если общий deadline истёк раньше, результат остаётся
Ignore: одного такого события недостаточно, чтобы обвинить backend.

## Lifecycle и ограничения

Общий `CommandResilienceDecorator` обслуживает именованные политики; команда создаётся
один раз на HTTP-запрос. При закрытии декоратор прекращает приём, запрашивает отмену
активных задач и останавливает scheduler. Очистка остаётся кооперативной. В app Spring
сначала закрывает `ProxyCommands`/декоратор, затем `ProxyEngine`.

`CommandConfigRegistry` удерживает lease до фактической очистки; `clearCache(prefix)`
и `clearAllCaches()` атомарно отклоняются для используемых записей. `ProxyCommands`
также удерживает lease на всё время жизни JMX-подписок: команды и диагностика всегда
используют одни и те же лимитеры. Смена свойств существующего имени без idle clear запрещена.

Retry выключен по умолчанию. Для повторяемых операций вне прокси нужны одновременно
`maxAttempts > 1` и `replayable=true`; повторяются только `RetryableException`.
Новая попытка начинается после cleanup предыдущей; общий deadline включает паузы и
ожидания. Fallback применяется к окончательному результату. Для Servlet HTTP body
повторы остаются выключенными независимо от метода запроса.

Fallback находится за пределами timeout/Bulkhead/CB/Retry исходной команды.
Он может начаться до завершения её cleanup; для собственного сетевого вызова fallback
нужна отдельная ограниченная политика. Возврат исходного permit не ожидает fallback.
Отмена подписки отменяет активный fallback и не инициирует новый.

`start()`, `cancel()`, event listeners Resilience4j и callbacks CompletionStage должны
быть неблокирующими. TimeLimiter не умеет безопасно прервать синхронный Processor,
зависший до возврата handle; такое расширение нарушает контракт. JVM pause или
блокировка event loop также может задержать применение отмены к сети.

Bulkhead допускает максимум заданного числа незавершённых задач и не ждёт свободного
слота. У proxy сохранены собственные лимиты admission/pool: они защищают транспорт
и при использовании библиотеки без обвязки команд. Уменьшение одного лимита не меняет другой.

Нативные метрики приложения доступны через `ProxyCommands.bulkhead()`, `circuitBreaker()` и
`timeLimiter()`; универсальный API предоставляет реальные объекты через registry lease `holder()`. Не добавляйте бросающие исключения или блокирующие event listeners;
это нарушает контракт Resilience4j и может затормозить I/O. HTTP endpoint мониторинга
в библиотеках не регистрируется.

## Проверки

Добавлены unit-тесты lifecycle команды и сетевые тесты с настоящим Jetty/upstream:
Bulkhead saturation, timeout с отменой, открытие/восстановление CircuitBreaker,
4xx без открытия circuit, удержание слота на streaming body и abort после commit.
Актуальные количества и результат полной сборки — в `verification.json`.
Независимый аудит шести ролей, исправления и регрессии — в
[RESILIENCE_REVIEW.md](RESILIENCE_REVIEW.md).
Итоговая оценка седьмого рецензента — в [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md).
Нагрузочные и soak-измерения этим расширением не заменяются.

Официальные материалы:
[TimeLimiter](https://resilience4j.readme.io/docs/timeout),
[Bulkhead](https://resilience4j.readme.io/docs/bulkhead),
[CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker),
[релиз 2.4.0](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0).
