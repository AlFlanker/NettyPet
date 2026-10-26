# Повторная оценка адаптации пятью специалистами

Проверяемая версия: PR #10, `fd8ed04`. Исправления runtime: `c2dcfb3`.
Ревью охватывает код, реальные HTTP-сценарии и контракты Jetty 12.1.12.

## Решение совета

Граница Filter → prepared plan → engine подходит основной async-цепочке
`examples/proxy/HttpProxyFilter.java`. Она сохраняет выбор backend и прикладную
подготовку перед транспортом. Выбор старого или нового executor возможен до
передачи владения; сам feature toggle по-прежнему не реализован.

Первоначальные 150 тестов не покрывали все ошибки интеграции. Найдены и исправлены
десять классов дефектов. Оснований считать весь портал перенесённым нет:
его алгоритмы HTML/CSS, cache, синхронный `load`, Security и Resilience4j остаются
явными задачами подключения, описанными в [MIGRATION.md](MIGRATION.md).

| Специализация | Результат проверки |
| --- | --- |
| Архитектура адаптации | Основная async-цепочка совместима; воспроизведены потери application headers/cookies и `Vary` |
| Servlet / Jetty I/O | Найдено зависание при ошибке response wrapper; новых ошибок chunk ownership и backpressure не обнаружено |
| Lifecycle и отмена | Найдены незавершённый supplied async на preflight error и обрыв cleanup при ошибке release |
| HTTP / headers / cookies | Найдены несогласованные HEAD/304 metadata и cache validators, ошибка quoted Cache-Control |
| Память / CPU / пулы | Подтверждены накопление destinations и удержание отменённых queued rewrite; проверены CAS и время жизни input |

## Найденные сценарии и исправления

| Приоритет | Сценарий до исправления | Последствие / исправление |
| --- | --- | --- |
| P1 | Servlet accessor бросает исключение до регистрации Exchange, а приложение уже передало async с timeout `0` | Запрос остаётся без владельца. Preflight теперь возвращает результат с причиной, завершает принадлежащий ему async и освобождает permit |
| P2 | Response wrapper бросает из `isCommitted()` во время обработки upstream failure | Внутренний cleanup завершается, клиентское соединение остаётся висеть. Все ошибки Servlet error path теперь приводят к abort downstream |
| P2 | Локальный `502/504` вызывает `response.reset()` после предыдущих фильтров | Исчезают application cookies, CORS и security headers. Начальные headers сохраняются и восстанавливаются; metadata прежнего тела удаляется, локальная ошибка получает `Cache-Control: no-store` |
| P2 | Upstream возвращает `Vary: Accept-Encoding`, приложение уже выставило `Vary: Origin` | Теряется часть ключа shared cache. `Vary` теперь сохраняет значения приложения и upstream |
| P2 | Два worker заняты, третий rewrite стоит в очереди и отменяется | Его input, admission и completion ждут чужого worker. CAS разделяет queued/running: queued task удаляется и освобождается сразу; running task сохраняет input до возврата |
| P2 | Planner последовательно выбирает новые origins | По умолчанию Jetty destination eviction выключен. Добавлен положительный `destination-idle-timeout`, по умолчанию `60s`, с реальным тестом автоматического удаления пустых destinations |
| P2 | GET преобразован в тело другой длины, HEAD/304 передаёт metadata исходной репрезентации | Исходные length/validators больше не выдаются для потенциально преобразуемого HEAD/304. Для полного GET/HEAD убираются origin-specific cache validators; wildcard existence condition и write preconditions сохраняются |
| P3 | `Cache-Control: private="ETag, no-transform, X-Test"` | Наивный split принимал текст внутри quoted argument за директиву и пропускал нужный rewrite. Используется Jetty `QuotedCSV` |
| P2 | Освобождение rewrite slab бросает исключение | Исключение из `drain.finally` оставляет WIP, permit и результат незавершёнными. Collector сначала отсоединяется; ошибка release попадает в outcome, остальные стадии cleanup продолжаются |
| P2 | Reusable engine получает незапущенный HttpClient без executor | Первый dispatch бросает NPE после запуска async. Отсутствующий executor теперь даёт `503` до начала владения новым async; исправление конфигурации позволяет последующим запросам работать |

Дополнительно public `ProxyCall` отделён от `Exchange`: после completion он
сохраняет результат, а cancellation delegate очищается. Хранение завершённых
handles больше не удерживает через engine request/response wrappers и их тела.
Это улучшение контракта удержания ссылок, а не заявление об измеренной утечке JVM.

## Подтверждение тестами

До исправлений соответствующих ветвей выполнены три целевых прогона:
31 проверка, **26 падений**, пять проходящих контролей. Отдельный probe Jetty
создал 2 000 пустых destinations без отправки запросов: при стандартном
`destinationIdleTimeout=0` все оставались в map.

Добавлено 38 тестовых invocations, включая проверку эффективного Range после
header hooks, wildcard, write preconditions, eviction и настройки нового срока.
Полный `gradle build` прошёл: **433 теста**, из них **188** в модуле;
failures/errors/skips — ноль. Состав модуля:

| Группа | Число |
| --- | ---: |
| HTTP integration / protocol / limits / rewrite / planner / cookies / application headers | 78 |
| Header hooks / engine / preflight / plan lifecycle / Filter routing | 63 |
| Буферы / properties / shutdown / failure cleanup / destination lifecycle | 47 |

Executable JAR SHA-256:
`8b36c0ab69a3fb9272eaebfe4b909b221b98c508e2c9f450e039bc5151d768c0`.

Повторный throughput benchmark для этого исправления не выполнялся.
[Предыдущий контроль](filter-integration-performance.json) 159,96 → 176,83 rps
относится к runtime `e078071`, до этого совета. Новых копирований всего тела
в обычный streaming path не добавлено; проверены восстановление admission и
безопасность памяти при отмене очереди и работающего worker.

## Ограничения, требующие решения при подключении

- **Range:** существующий контракт передаёт `206` без rewrite. Его bytes могут
  относиться к исходному телу, тогда как полный GET преобразуется. На маршрутах,
  где нужна единая преобразованная репрезентация, прикладной request hook должен
  удалить `Range` и `If-Range`, либо следует разделить URL/режимы. Engine учитывает
  итоговый Range после hooks при обработке conditional headers.
- **Работающий rewriter:** deadline не останавливает произвольный Java-код.
  Его память удерживается до фактического возврата; для queued task это ограничение
  устранено. Hooks и worker не должны выполнять blocking storage/network calls.
- **Проверка кеша:** наличие body policy консервативно отключает origin-specific
  conditional GET оптимизацию даже если MIME в итоге передаётся потоком. Применяйте
  policy к нужным маршрутам; генерация валидаторов преобразованного тела не реализована.
- **Servlet integration:** supplied async передаётся из исходного dispatch до его
  возврата. Формы и multipart нельзя предварительно читать. Глобально отключённый
  `FormContentFilter` также влияет на локальные MVC form PUT/PATCH; в приложении
  нужны корректный порядок фильтров и раздельная обработка proxy/local routes.
- **Существующие границы:** GET reset до headers при отсутствии I/O может
  обнаружиться только по deadline; HTTP/2 upload, tunnels и WebSocket не поддержаны.

Error status/body остаются контрактом примера (`502/504/503`, пустой локальный
ответ), а не переносом legacy `408/500` и его текстовых fallback-страниц.
