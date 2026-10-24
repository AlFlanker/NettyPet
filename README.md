# Async HTTP reverse proxy

Потоковый reverse proxy на Java 21, Spring Boot **4.1.1**, Spring WebMVC,
Jetty **12.1.12** из BOM Spring Boot, Netty **4.2.19.Final**
и Resilience4j **2.4.0**. Приложение использует модули `app`, `proxy` и `command-support`.
Модули Netty согласованно зафиксированы через Gradle `enforcedPlatform` и Netty BOM.
Jetty принимает запросы, Netty устанавливает соединения с upstream.
Тела запросов и ответов передаются порциями; размер тела не определяет размер буферов прокси.

## Запуск

Нужны JDK 21 и доступ к репозиториям зависимостей. Gradle Wrapper **8.14.3** входит
в проект; устанавливать Gradle отдельно не требуется. В Windows используйте `gradlew.bat`.

```bash
./gradlew clean build
PROXY_UPSTREAM=http://localhost:9000 java -jar app/build/libs/app-1.0.0.jar
```

Для запуска из исходников:

```bash
PROXY_UPSTREAM=http://localhost:9000 ./gradlew :app:bootRun
curl -i 'http://localhost:8080/proxy/example?x=1&x=2'
curl --data-binary @large-file.bin http://localhost:8080/proxy/upload
curl -i http://localhost:8080/local
```

Upstream должен быть запущен отдельно. По умолчанию прокси слушает `8080`, upstream — `9000`.
`PROXY_UPSTREAM=https://backend.example/base` добавляет `/base` перед путём после удаления `/proxy`.
Raw query и существующие `%XX`-последовательности сохраняются без повторного кодирования.

## Состав проекта

| Gradle-модуль | Ответственность | Результат сборки |
| --- | --- | --- |
| `app` | Spring Boot, Jetty, MVC, фильтр, композиция команд и прокси, настройки и Processor beans | `app/build/libs/app-1.0.0.jar` — исполняемое приложение |
| `proxy` | `ProxyEngine`, SPI, Netty, async Servlet I/O, обратное давление и пулы | `proxy/build/libs/proxy-1.0.0.jar` — обычная библиотека |
| `command-support` | Reactor Command API, ресурсный lifecycle, TimeLimiter, Bulkhead, CircuitBreaker и opt-in Retry | `command-support/build/libs/command-support-1.0.0.jar` — библиотека приложения |
| `jetty-client-example` | Самостоятельный пример Servlet + Jetty HttpClient без Netty и Spring Security | `jetty-client-example/build/libs/jetty-client-example-1.0.0.jar` — отдельное приложение |

[`jetty-client-example`](jetty-client-example/README.md) запускается отдельно на порту `8081`.
В нём показана потоковая передача GET и POST/PUT через Jetty HttpClient;
описаны команды запуска, проверки multipart и границы примера.

`app` зависит от `proxy` и `command-support`. Между транспортом и библиотекой команд нет зависимости: стык находится только в `app`.
Модуль `proxy` не зависит от Spring Boot или Jetty. Его код использует Netty,
Servlet API и SLF4J; Servlet-контейнер предоставляет приложение.
Он не знает префикс `/proxy`, MVC-маршруты, команды или Resilience4j.
`command-support` не знает Servlet, Netty, HTTP или proxy. Его API работает без Spring;
Spring-конфигурация и Micrometer подключаются отдельно. В приложении используется ресурсный
вариант команды, который удерживает лимит до окончания асинхронной очистки.

| Пакет | Ответственность |
| --- | --- |
| `proxy: dev.example.proxy.api` | Метаданные, `ProxyOptions`, заголовки и цепочка `RequestProcessor` |
| `proxy: dev.example.proxy.core` | Сборка URI, нормализация origin, правила HTTP-заголовков |
| `proxy: dev.example.proxy.transport` | `ProxyEngine`, async Servlet I/O, Netty, буферы и завершение обмена |
| `app: dev.example.app.config` | Spring-конфигурация, `ProxyProperties` и регистрация фильтра |
| `app: dev.example.app.web` | `ProxyFilter` и пример локального MVC-контроллера |
| `app: dev.example.app.command` | `ProxyRequestCommand` и HTTP-адаптер композиции |
| `command-support: com.otr.portal.command` | Реактивные команды, реестр политик, ресурсный lifecycle и отмена |
| `examples` | Отключённый по умолчанию пример заголовков и изменения URI |

## Маршрутизация приложением

`ProxyFilter` зарегистрирован на `/*` для `DispatcherType.REQUEST` с `asyncSupported=true`.
Он сопоставляет raw path относительно context path с точным `/proxy` или `/proxy/…`.
После совпадения удаляет только этот префикс и вызывает
`ProxyRequestHandler`, который синхронно подписывается на `ProxyRequestCommand.execute()`.
Только допущенная команда вызывает `ProxyEngine.forwardAsync(...)`. Остальные запросы передаёт дальше
через `FilterChain`, и их обслуживает обычный Spring MVC.

| Входящий запрос | Обработка при upstream `http://localhost:9000` |
| --- | --- |
| `/proxy` | Upstream `/` |
| `/proxy/` | Upstream `/` |
| `/proxy/a?x=1&x=2` | Upstream `/a?x=1&x=2` |
| `/proxy/a%20b?x=%2F` | Upstream `/a%20b?x=%2F` без повторного кодирования |
| `/proxying` | Обычная MVC-цепочка; это не префикс прокси |
| `/local` | Локальный MVC-контроллер отвечает `local` |

При `server.servlet.context-path=/gateway` прокси доступен по `/gateway/proxy/…`;
ни context path, ни `/proxy` в upstream path не попадают. Query string не изменяется.

Фильтр выполняется до фильтров, способных разобрать тело или изменить кодировку ответа.
Проксируемый запрос не проходит через `@RequestBody`, message converters или
`StreamingResponseBody`. При этом multipart, form-content и character-encoding support
не отключены глобально: для локальных MVC-запросов сохраняются настройки Spring Boot.
Тело остаётся непрозрачным: JSON, multipart, бинарные данные, сжатые представления.

Пример приложения не содержит аутентификации. Если она нужна, проверка доступа должна
выполняться **до** `ProxyFilter`: после перехвата `/proxy` оставшаяся filter chain не
вызывается. При добавлении Spring Security согласуйте порядок security/body-consuming
фильтров явно; одного подключения security-зависимости для этой схемы недостаточно.

Spring binding находится в `app`: `ProxyProperties.toOptions()` создаёт обычный
`ProxyOptions`, передаваемый библиотеке. `DownstreamAborter` также передаётся извне:
`app` предоставляет адаптер Jetty для прерывания уже начатого неполного ответа.
Здесь же подключается `JettyProxyIoAdapter`: он сообщает о поддержке direct input
и предоставляет неблокирующий writer с явным возвратом владения буфером.
Старый конструктор `ProxyEngine` сохраняет переносимый Servlet-путь.

## Как устроено обратное давление

После подготовки метаданных вызывается `startAsync()`. `ReadListener`, `WriteListener`
и async-callbacks подают сигналы; состояние обмена и операции I/O сериализованы одним
Netty `EventLoop`. Сигналы объединяются, обработчик не вызывается рекурсивно из write promise.
Короткий монитор на один exchange согласует Servlet I/O с завершением контейнера;
внутри нет ожидания сетевых Future. Ошибки `ReadListener`/`WriteListener` и синхронные
Servlet `IOException` отдельно прерывают async-ответ: это не просто уведомления о завершении.

**Клиент → upstream.** Чтение разрешено только при `ServletInputStream.isReady()`,
готовности Netty-канала и отсутствии предыдущего буфера в записи.
Одновременно передаётся максимум один body-буфер размером `chunk-bytes`.
Следующее чтение запускается после завершения Netty write promise либо смены writability.
Если upstream медленный, прокси перестаёт читать клиентское тело; давление доходит до TCP.
В Jetty тело читается сразу в pooled direct `ByteBuf` через его `ByteBuffer` view.
Это убирает промежуточный heap-буфер и последующую копию heap → direct в Netty NIO.
Для неизвестного/обёрнутого input сохраняется чтение в heap-буфер: стандартный
Servlet `read(ByteBuffer)` сам по себе не гарантирует отсутствие временного массива.

**Upstream → клиент.** У Netty установлен `AUTO_READ=false`.
Fixed receive allocator использует `chunk-bytes` и `maxMessagesPerRead(1)`.
Следующий `channel.read()` выдаётся после завершения текущего read batch,
опустошения очереди и возврата callback записи Jetty; в переносимом пути — после
готовности `ServletOutputStream`.
Один socket read может породить несколько HTTP chunks; TLS и декодер также имеют свои буферы.
Поэтому дополнительно ограничены байты очереди. В неё попадают только непустые
буферы: множество маленьких HTTP chunks сохраняет корректность, но увеличивает число объектов.

В Jetty исходный `ByteBuf` удерживается до callback записи его `ByteBuffer` view:
копии payload в промежуточный Servlet scratch нет. Несколько уже полученных мелких
фрагментов (каждый меньше 1 KiB) могут объединяться в один pooled direct buffer
размером не больше `chunk-bytes`, чтобы не создавать отдельную запись на каждый байт.
Для объединения нужны хотя бы два полностью помещающихся фрагмента; ожидания новых
данных нет. Крупные и одиночные фрагменты остаются исходными views.
Даже abort или окончание async
не разрешают вернуть этот буфер в pool до callback. Одновременно допускается одна
запись; завершение команды и возврат Bulkhead permit ждут её cleanup.
`Response.write` Jetty уже публикует переданную порцию: после её подтверждения
не выполняется дополнительная пустая запись `flush`. Адаптеры, которые могут
буферизовать данные, сохраняют явный `flush` после каждой группы чтения.

Порция по умолчанию — 64 KiB: для больших тел это уменьшает число чтений сокета,
записей и callback по сравнению с 16 KiB. Чтение не ждёт заполнения порции,
поэтому короткие события по-прежнему передаются сразу. Общий лимит очереди ответа
остаётся 256 KiB; для меньшего upload/scratch можно задать `--proxy.chunk-bytes=16384`.

Для response wrappers и другого Servlet-контейнера используется переносимый путь.
Он выделяет один `byte[]` scratch только при первой порции тела и переиспользует его
после `isReady()==true`. Wrapper не распаковывается: его преобразования тела сохраняются.
После каждого полученного batch выполняется flush, чтобы короткие события и заголовки
не ожидали завершения всего ответа. Это сокращение копий между библиотеками;
**сквозной zero-copy через TLS, контейнер и ядро ОС не заявляется**.

Пределы по умолчанию на обмен: до 64 KiB upload, до 256 KiB payload в очереди ответа
и, только в переносимом пути с непустым телом, 64 KiB output scratch.
Это не предел полной памяти JVM:
отдельно существуют headers, объекты, pooled allocations, Jetty, TLS и socket buffers.
Превышение защитного лимита batch завершает обмен ошибкой `502` до commit ответа;
после commit соединение прерывается, поскольку заменить уже отправленный статус нельзя.

## Настройки

Все параметры находятся в `app/src/main/resources/application.yml` и поддерживают стандартное
переопределение Spring Boot через environment variables и аргументы `--proxy.*`.

| Параметр `proxy.*` | По умолчанию | Назначение |
| --- | --- | --- |
| `upstream` | `http://localhost:9000` | Основной адрес с необязательным префиксом пути |
| `io-threads` | `2` | Число Netty I/O loops |
| `max-concurrent` | `256` | Общий предел принятых обменов, включая ожидание pool |
| `connections-per-origin-per-loop` | `64` | Предел соединений одного origin на одном loop |
| `pending-acquires-per-pool` | `64` | Предел ожидающих получения соединения |
| `chunk-bytes` | `65536` | Размер upload/scratch/receive буфера |
| `response-buffer-bytes` | `262144` | Защитный предел payload очереди ответа |
| `connect-timeout` | `3s` | TCP connect, TLS handshake и отдельный DNS query |
| `acquire-timeout` | `5s` | Ожидание соединения из pool |
| `response-headers-timeout` | `10s` | Ожидание финальных upstream headers после полной отправки запроса |
| `exchange-timeout` | `60s` | Абсолютный срок обмена после запуска async-транспорта |
| `idle-connection-timeout` | `30s` | Закрытие простаивающего upstream-соединения |
| `allowed-origins` | `[]` | Дополнительные разрешённые `scheme://host:port` |

Пулы привязаны к `(origin, EventLoop)`. По умолчанию это до `64 × 2 = 128`
соединений на origin. Лимит `256` ограничивает обмены, а не общее количество
простаивающих соединений во всех пулах. При заполнении admission limit ответ — `503`;
pool имеет отдельные ограниченную очередь и timeout.
Разрешено не более 32 дополнительных origins; пулы создаются по мере обращения.

Origin основного upstream разрешён автоматически. Изменять path/query можно без
добавления origin. Для изменения scheme/host/port нужный адрес необходимо добавить:

```yaml
proxy:
  allowed-origins:
    - https://alternate.example:443
```

Allowlist сравнивает origin после нормализации порта и регистра host/scheme;
это список доверенных назначений, а не фильтр IP-адресов после DNS resolution.
HTTP/S URI с user info или fragment отвергаются.
DNS resolution асинхронный: TTL положительного кеша ограничен 60 секундами,
отрицательного — 5 секундами; на одно разрешение допускается до 16 DNS queries.
Это не единый DNS deadline: общий срок обмена всё равно ограничен `exchange-timeout`.

## Добавление Processor

Добавьте в модуль `app` Spring beans типа `RequestProcessor`; порядок задаётся `@Order`
и собирается через `ObjectProvider.orderedStream()`.
Процессоры выполняются синхронно до отправки запроса. Они должны быть thread-safe,
не сохранять request/chain для будущего вызова и выполнять короткую работу над метаданными.
Блокирующие HTTP/DB-вызовы и чтение тела в этот контракт не входят.
`proxy.exchange-timeout` ещё не действует во время Processor. Таймер команды уже
запущен, но не может прервать синхронный блокирующий Processor: отмена будет доставлена
после возврата управления. Время его работы и объём
добавляемых заголовков контролирует приложение; лимиты тела не ограничивают произвольный
код расширения.

```java
@Bean
@Order(10)
RequestProcessor additionalHeaders() {
    return (incoming, outgoing, chain) -> {
        outgoing.headers().set("X-Proxy-Node", "edge-a");
        chain.proceed();
    };
}
```

`set()` заменяет все значения, `add()` добавляет отдельное значение,
`remove()` удаляет поле. Имена регистронезависимы; CR/LF и недопустимые символы запрещены.
`IncomingRequest` содержит immutable metadata; `ProxyRequest` — изменяемые URI и headers.
Нужно вызвать `chain.proceed()` ровно один раз или бросить `ProxyRejection(status, message)`.
Текст исключения не отправляется клиенту. Можно выполнить код до и после `proceed()`.
Ошибка цепочки сохраняется: внешний Processor не может разрешить отправку, проглотив
исключение вложенного Processor. Это относится и к отказу после `proceed()`, и к
повторному вызову продолжения во время выполнения цепочки. Первый `Error` имеет
приоритет над ранее сохранённым обычным исключением и пробрасывается вызывающему коду.
Поздний вызов сохранённого `chain` запрещён и бросает ошибку, но не отменяет запрос,
который уже был отправлен после успешного завершения цепочки.

Полный пример находится в [`examples/ProxyCustomization.java`](examples/ProxyCustomization.java).
Для включения скопируйте его в
`app/src/main/java/dev/example/app/custom/ProxyCustomization.java`.
Он добавляет заголовки и заменяет `/public` на `/internal`, сохраняя raw path/query.
Например, входящий `/proxy/public/a%20b?x=1&x=2` отправляется как `/internal/a%20b?x=1&x=2`.
`IncomingRequest.rawPath()` уже не содержит `/proxy` или context path.
Сопоставление выполняется с уже собранным upstream path, включая его базовый префикс.

При кастомизации используйте `getRawPath()`/`getRawQuery()`.
Многокомпонентный `new URI(...)` повторно закодирует `%` в уже закодированном пути.
Не используйте `URI.resolve()` с непроверенным `//host/path` как замену соединения путей.
После всей цепочки транспорт повторно проверяет URI/origin и нормализует заголовки:
`Host` и framing выставляются самим транспортом; hop-by-hop поля и поля из `Connection`
удаляются. Процессор не может обойти эти правила добавлением нового `Connection`.
После обработки создаётся неизменяемый снимок URI, headers и framing. Выбор пула,
`Host` и request-target используют один и тот же URI. Позднее изменение сохранённой
прикладным кодом ссылки на `ProxyRequest` не меняет уже подготовленный сетевой запрос;
сохранять и изменять такую ссылку всё равно не входит в контракт Processor.

## Команды и Resilience4j

В `command-support` интерфейс `Command<T>` возвращает холодный `Mono<T>`.
`AbstractResilienceCommand` подходит для обычных реактивных операций, а
`AbstractResourceCommand` связывает отмену с подтверждённой очисткой через `CommandTask`.
В `app` ресурсная команда одного запроса — `ProxyRequestCommand`;
`ProxyCommandConfiguration` задаёт лимиты и сопоставляет результаты proxy с политикой CircuitBreaker.
Полное API, retry и правила смены конфигурации: [`command-support/README.md`](command-support/README.md).

- **Bulkhead** — semaphore с нулевым ожиданием. Слот удерживается до завершения всего
  тела и cleanup, даже если TimeLimiter уже завершил результат команды таймаутом.
- **TimeLimiter** — абсолютный бюджет команды. Его timeout запрашивает реальную
  отмену `ProxyCall`; у ещё активного HTTP-обмена до commit клиент получает `504`,
  после commit поток прерывается. Если ответ уже завершён, а остаётся только cleanup,
  timeout результата команды не изменяет отправленный ответ.
- **CircuitBreaker** — учитывает upstream `5xx` и сетевые/протокольные ошибки. Отмена
  клиента, локальные отказы и общий deadline потока исключены из его статистики.
  Отдельный timeout ожидания headers после полного upload считается upstream failure:
  молчащий backend открывает circuit, а медленный upload/download клиента — нет.
  При OPEN запрос не запускает транспорт и получает `503`.

Параметры находятся в `proxy-command.*`: `timeout=30s`, `max-concurrent=128`,
`failure-rate-threshold=50`, `sliding-window-size=20`, `minimum-number-of-calls=10`,
`wait-duration-in-open-state=10s`, `permitted-calls-in-half-open-state=3`.
Одна именованная политика защищает логическую группу запросов `/proxy`.
Автоматический retry выключен: Servlet request body одноразовый. Подписка выполняется
на Servlet-потоке до возврата фильтра, без переключения scheduler до `startAsync`.
`proxy.max-concurrent=256` остаётся независимым защитным лимитом самого транспорта.

Подробные контракты, порядок применения политик, классификация отказов и примеры:
[`RESILIENCE.md`](RESILIENCE.md).

## Метрики

Один `ProxyEngine` создаётся на приложение. Его агрегированные счётчики доступны
через `metrics()`, а текущее количество занятых admission-слотов — через `activeRequests()`:

```java
ProxyMetrics.Snapshot snapshot = proxyEngine.metrics();
int active = proxyEngine.activeRequests();
```

| Поле `Snapshot` | Значение |
| --- | --- |
| `admitted` | Полученные admission permits, включая ожидание соединения |
| `rejected` | Отказы до запуска async-обмена; permit к этому моменту уже мог быть получен |
| `completed` | Все завершённые async-обмены, включая ошибки и отмену |
| `cancelled` | Завершённые обмены, отменённые со стороны контейнера |
| `failed` | HTTP `5xx`, включая отказы до async и завершившиеся async-обмены |
| `totalUploadBytes` | Байты, прочитанные из входящего Servlet stream |
| `totalDownloadBytes` | Байты, переданные выходящему Servlet stream; это не подтверждение TCP ACK |
| `totalDurationNanos` | Сумма длительностей завершённых обменов по монотонным часам |
| `maxQueuedResponseBytes` | Исторический максимум payload очереди среди завершённых обменов |

Счётчики накопительные, без сброса и без меток URI/host/client: количество рядов
не растёт от разнообразия запросов. `Snapshot` не является атомарным снимком всех
потоков; при параллельном обновлении поля могут относиться к немного разным моментам.
`admitted` и `rejected` пересекаются, а `completed` не означает только успешные ответы.
Поэтому текущую нагрузку нельзя вычислять вычитанием счётчиков.

`activeRequests()` читает фактически занятые admission-слоты, включая отменённые
запросы, чей pool acquire ещё не завершился. Это отдельный точный gauge на момент
чтения. В `app` подключён read-only JMX bridge `ProxyDiagnostics`: шаблон имени
`dev.example.proxy:type=Diagnostics,*`. Он также экспортирует Bulkhead permits/rejections,
CircuitBreaker state/window/rejections, TimeLimiter events и локальный Spring
readiness/liveness. Состояние backend не подменяет готовность локального приложения.

`spring.jmx.enabled=true` и `spring.jmx.unique-names=true` включены по умолчанию;
удалённый connector и HTTP endpoint не добавляются. Каждый контекст имеет свой MBean,
удаляемый при остановке. `--spring.jmx.enabled=false` отключает bean и его event subscribers.
Подключение к системе мониторинга стенда и интерпретация атрибутов описаны в
[`TRIALS.md`](TRIALS.md). Для p99, RSS/direct memory и клиентских ошибок нужен отдельный
сбор измерений; duration counter не является latency histogram.

## Завершение и границы поддержки

- Поддерживаются обычные HTTP-запросы с произвольным методом, path/query и потоковым телом,
  если запрос прошёл парсер и ограничения Jetty. `OPTIONS *` не входит в текущую реализацию.
- Upstream использует HTTP/1.1, без pipelining и multiplexing. HTTP/2 upstream, gRPC,
  CONNECT, WebSocket и протокольные upgrades не реализованы. CONNECT/входящий Upgrade — `501`;
  upstream `101` — ошибка. Это HTTP reverse proxy, не универсальный TCP-туннель.
- Промежуточные upstream `1xx` не пересылаются. `Expect: 100-continue` обрабатывается
  на входящей стороне контейнером; upstream `Expect` удаляется. Другие expectations — `417`.
- Request/response trailers сейчас не пересылаются. Повторяющиеся обычные заголовки,
  включая `Set-Cookie`, сохраняются отдельными значениями.
- Поддерживается только отсутствующий `Transfer-Encoding` либо единственный `chunked`.
  Другие transfer codings дают `501` для входящего запроса и `502` для upstream-ответа.
  `Content-Encoding`, включая gzip, передаётся без изменения вместе с исходными байтами.
- Upstream HTTP decoder строго проверяет строки и заголовки; сочетание
  `Transfer-Encoding` с `Content-Length` в ответе с телом и повторный `Content-Length` отклоняются.
  Для HEAD и безтельных статусов действуют отдельные правила decoder.
  Ответ `205` с ненулевым `Content-Length` также считается ошибкой upstream (`502`).
  Статусы вне диапазона `100..599` отклоняются с `502`.
- Общий пул не закрепляет upstream-соединение за клиентом. Схемы `Authorization`
  `NTLM` и `Negotiate` отклоняются с `501` после Processor и до сетевого I/O:
  аутентификация с состоянием на соединении требует отдельной реализации connection binding.
  Обычные Basic/Bearer-заголовки сохраняются. Подробнее о требуемой привязке:
  [документация NTLM upstream](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#ntlm).
- Jetty может отклонить неоднозначные или запрещённые URI ещё до фильтра. Например,
  `/proxy/a%252Fb` получает `400` до вызова Processor; `/proxy/a%20b` передаётся как `/a%20b`.
  Прокси не ослабляет URI compliance контейнера.
- При раннем финальном upstream-ответе upload останавливается, ответ дочитывается,
  upstream-соединение не возвращается для повторного использования. Downstream HTTP/1
  соединение с непрочитанным телом закрывается.
- Нет автоматических повторов: невозможно безопасно повторить произвольный уже начатый
  POST или другое потоковое тело. Закрывшееся idle-соединение может дать ошибку первого запроса.
- Ошибки до commit дают соответствующий `4xx/5xx`; ошибки после commit прерывают ответ.
  Недоступность или некорректный ответ upstream дают `502`, его timeout — `504`,
  переполнение admission/pool либо timeout ожидания свободного pool-слота — `503`.
  Адаптер `DownstreamAborter` в `app` использует Jetty API; для другого servlet container
  потребуется другой адаптер, без изменений транспортного модуля. Адаптер должен быть
  неблокирующим и не ждать AsyncListener callbacks либо Netty Future.
- HTTPS upstream использует JDK TLS provider, TLS 1.2/1.3, стандартные доверенные CA
  и обязательную проверку имени хоста.
  Trust-all, отключение проверки сертификата и автоматическое следование redirect не включены.
- `close()` прекращает новые обмены и закрывает все upstream-каналы, в том числе
  занятые и подключающиеся, затем DNS resolver и event loops.
- SSE и другие длительные ответы ограничены абсолютными сроками: по умолчанию
  `proxy-command.timeout=30s` и резервный `proxy.exchange-timeout=60s`.
  Периодические события не продлевают срок; ожидание первой задачи event loop входит
  в бюджет. Таймер исполняется на том же loop: остановка JVM или блокировка самого
  loop задержит и таймер. Задайте подходящий timeout явно.

Пулы рассчитаны на доверенные upstream из allowlist. Строгий HTTP decoder проверяет
framing, но не гарантирует изоляцию запросов, если upstream намеренно отправляет
незапрошенные или отложенные дополнительные ответы по повторно используемому HTTP/1.1
соединению. Такую гарантию эта реализация не заявляет.

## Проверки и производительность

`./gradlew clean build` запускает unit-тесты API/HTTP-правил и интеграционные тесты с настоящим Jetty
и локальными upstream-сокетами: большой binary body, chunked upload, ранний ответ,
URI/headers, первая порция до окончания ответа, HEAD/204, timeout, повторное использование
соединений и обрыв неполного ответа после commit. Тесты приложения также проверяют
границу префикса `/proxy`, локальные MVC-маршруты, context path и сохранение raw query.
Отдельные детерминированные тесты
проверяют остановку upload при занятом Netty-канале, сохранность scratch во время
асинхронной записи, остановку upstream-read и освобождение ByteBuf при отмене.
В тестах включён Netty leak detector; Mockito подключается явным Java agent, без self-attach.
Сетевые сценарии используют HTTP/1.1. HTTP/2 downstream и TLS нагрузочные сценарии
не проверены. Реальные HTTPS-тесты проверяют streaming и reuse, недоверенный сертификат,
несовпадение hostname, отмену после commit и освобождение обоих admission-лимитов.

Результаты ролевого аудита, найденные дефекты и подтверждающие проверки:
[`REVIEW.md`](REVIEW.md), [`RESILIENCE_REVIEW.md`](RESILIENCE_REVIEW.md).
Решение седьмого рецензента о готовности к эксплуатации:
[`PRODUCTION_READINESS.md`](PRODUCTION_READINESS.md).
Последнее независимое ревью после оптимизации, подтверждённый дефект idle-пула,
исправления и **168 прошедших тестов**: [`INDEPENDENT_REVIEW.md`](INDEPENDENT_REVIEW.md).
Допуск к изолированным испытаниям и критерии перехода в production:
[`TRIALS.md`](TRIALS.md).

Оптимизации владения буферами, результаты локального сравнения аллокаций и команды
воспроизведения описаны в [`OPTIMIZATIONS.md`](OPTIMIZATIONS.md).
Это ограниченный HTTP/1.1 эксперимент; нагрузочные и длительные soak-тесты
целевого окружения не проводились. Перед подбором лимитов измерьте
throughput, p95/p99, heap/direct memory и поведение при медленных клиентах/upstream,
используя реальные размеры тел и HTTPS. Увеличение числа соединений само по себе не ускоряет backend.

Проверка фактических зависимостей:

```bash
./gradlew :app:dependencies --configuration runtimeClasspath
./gradlew :proxy:dependencies --configuration runtimeClasspath
./gradlew :command-support:dependencies --configuration runtimeClasspath
```

Документация: [Spring Boot web servers](https://docs.spring.io/spring-boot/how-to/webserver.html),
[managed dependencies](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html),
[Jakarta Servlet 6.1](https://jakarta.ee/specifications/servlet/6.1/),
[Netty ChannelConfig](https://netty.io/4.2/api/io/netty/channel/ChannelConfig.html).
