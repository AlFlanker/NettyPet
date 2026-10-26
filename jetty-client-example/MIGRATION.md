# Подключение библиотеки к логике приложения

Исходная цепочка из `examples/proxy/` начинается в `HttpProxyFilter.java`.
Теперь transport находится в обычном [jetty-proxy JAR](../jetty-proxy/README.md),
а [Spring adapter](../jetty-proxy-spring/README.md) включается явно и не объявляет
маршруты. Executable demo не является зависимостью host-приложения.

Реальный портал не предоставлен. Поэтому результат — reusable library и
[собираемая host-проверка](../jetty-proxy-integration-example/README.md) с собственным
Filter, двумя настоящими transports и отдельным loader. Это не заявление, что все
закрытые сервисы, SSO, cache и resilience портала уже перенесены.

## Соответствие существующим классам

| Пример приложения | Подключение |
| --- | --- |
| `HttpProxyFilter.shouldServeLocally()` / maintenance | Проверки остаются в application Filter до handoff |
| Backend из attribute/session, forbidden URI | Application resolver готовит immutable маршрут, отказ оформляет до async |
| `ProxyRequestExecutorLookup` | Выбор handler переносится к application boundary; library не содержит toggle |
| `HttpProxy.proxyRequest()` | Selected handler вызывает `JettyProxyEngine.forward()` один раз |
| `NettyProxyRequestExecutor` | Остаётся старой веткой; не вызывается как fallback после начала нового обмена |
| `cutBackendUri()` / `fullBackendUrl()` | Explicit raw target из выбранной backend-конфигурации |
| URI override/backend timeout | `ProxyRequestPlan.Builder.timeout()` |
| `collectHeaders()` / `PortalHeaderContributor` | Подготовленный контекст и обычный request header hook |
| `consumeHeaders()` / cookie interceptors | Response hook либо async header stage с immutable patch |
| `ResponseBodyRewriter`, FullLink/Html rules | `LinkRewritePolicy` и `HeaderRewriteCustomizer`; дополнительные правила приложения остаются его кодом |
| `ResponseOwnership` / competing fallback | Engine один владеет ответом; configurable `ProxyErrorPolicy` до commit |
| `RequestCallback`, lifecycle/metrics/tracing | Immutable `ProxyExchangeResult` через `ProxyCall.completion()` |
| `ChannelManager`, Netty pipeline | Shared Jetty `HttpClient` из `JettyProxyClientFactory` |
| `HttpProxy.load()` → String | Отдельный `ProxyResourceLoader` поверх bounded `ProxyFetchClient` |

Портальные исходники не включаются в production source sets. Только cache helpers
из `examples/proxy/caching` компилируются отдельным test fixture host-модуля.
Library не создаёт портальные `Backend`, registry или cache и не требует их
классов на classpath.

## Application Filter и однократный выбор

Рекомендуемая граница — собственный `HttpProxyFilter`, до чтения тела и async:

```text
local request → обычная MVC chain
maintenance / missing backend / forbidden → локальный ответ приложения
готовый BackendRoute → один выбранный ProxyRequestHandler
  ├─ новый handler → JettyProxyEngine
  └─ старый handler → существующий Netty transport
```

Так устроен `jetty-proxy-integration-example`: новая и legacy ветви используют
реальные transports. По умолчанию выбрана Jetty ветка; legacy подключается отдельной
startup configuration. Runtime feature toggle не реализован. Для его добавления
выберите handler один раз до `handle()` и сохраните решение на весь запрос.
При ошибке нельзя отправлять тот же POST/PUT другим транспортом: часть тела уже
могла уйти backend. Изменение toggle должно влиять только на новые запросы.

Демонстрационный `LegacyProxyHandler` имеет fixed backend и timeout своего Netty
engine. Другой выбранный target или deadline он отклоняет `501` с
`X-Proxy-Capability: fixed-backend-and-timeout-only` до handoff. Новый Jetty handler
принимает настройки конкретного маршрута; перенос legacy adapter требует явной
реализации таких возможностей.

Для простого маршрута доступен generic `JettyProxyFilter(executor, planner)`.
Planner возвращает plan либо `null` для local chain. Но конкретные URLs, порядок
Filter и maintenance/authorization принадлежат host, не library configuration.

Не вызывайте `getParameter*()`, `getParts()` или предварительное чтение input до
proxy. Host-пример ставит Filter перед MVC form parsing: проксируемый form PUT
сохраняет байты, а локальный MVC продолжает получать параметры. Demo глобально
отключает `FormContentFilter`; эту demo-настройку не нужно переносить в приложение.

## Общий client и настройка перед start

Engine и bounded fetch требуют factory-owned `HttpClient`. Его создают один раз
для TLS profile через `JettyProxyClientFactory`; lifecycle принадлежит приложению.
Для собственной конфигурации до start есть overload с `Consumer<HttpClient>`:

```java
var properties = JettyProxyProperties.defaults();
var client = JettyProxyClientFactory.createStarted(properties,
        ProxyTlsProfile.systemTrust(), upstream -> upstream.setName("portal-proxy"));
var engine = new JettyProxyEngine(client, properties, List.of(),
        new ProxyRewriteOptions(2, 8, 128L * 1024 * 1024));
```

В Spring adapter этот же pre-start этап задаётся optional именованным bean:

```java
@Bean("jettyProxyHttpClientCustomizer")
Consumer<HttpClient> proxyClientCustomizer() {
    return upstream -> upstream.setName("portal-proxy");
}
```

Customizer завершает настройку до запуска; обычные per-request headers меняются
через отдельные hooks. Библиотека устанавливает обязательный proxy-контракт:
empty cookie store, без redirect/автоматического auth/декодирования. При shutdown
сначала закрываются engine/fetch, затем останавливается общий client.

## Plan и существующий AsyncContext

Транспортная сигнатура:

```java
ProxyCall forward(HttpServletRequest request,
                  HttpServletResponse response,
                  AsyncContext supplied,
                  ProxyRequestPlan plan);
```

`target` берётся из доверенной backend-конфигурации. Используйте raw path/query;
`URI.resolve()` с начальным `//` меняет authority, а сборка из decoded компонентов
может повторно кодировать `%`. План не читает Servlet позднее: снимите backend,
frontend URL, user/client/request-id и tracing context на исходном потоке.

`forward()` вызывается на исходном dispatch thread до его возврата. При `supplied
== null` engine делает `startAsync(request,response)`. Если оставляете старое начало
async в `HttpProxy`, замените `req.startAsync()` на `req.startAsync(req,resp)` и
передайте тот же context. Wrappers не разворачиваются. Чужой context отклоняется
без завершения; новый async поверх уже начатого не создаётся.

Пример подготовки:

```java
var plan = ProxyRequestPlan.builder(target)
        .timeout(Duration.ofSeconds(15))
        .customizer(new ProxyHeaderCustomizer() {
            @Override public void customizeRequest(ProxyRequestMetadata metadata, ProxyHeaderEditor headers) {
                headers.set("X-Request-ID", preparedRequestId);
            }
        })
        .build();
ProxyCall call = selectedExecutor.forward(request, response, supplied, plan);
```

После передачи engine никто не продолжает chain и не пишет competing fallback.
Настройки timeout положительные, от 1 мс, с проверкой nanos overflow. Legacy `0`
как бесконечный timeout не переносится буквально.

## Header/cookie rules и внешний storage

Глобальные hooks вызываются по `@Order`, затем per-plan hooks. Editor существует
только на время вызова. Он защищает Host, framing и metadata непрозрачного тела;
итоговые поля проходят повторную sanitize с исходными Connection nominations.
Новые обычные headers не требуют изменения транспортного алгоритма.

`HeaderRewriteCustomizer` даёт общий mapping Origin/Referer/Location по immutable
backend/frontend bases и просит identity при rewrite, только если клиент его
допускает. При запрете identity negotiation сохраняется, link policy выбирает
passthrough; сжатая преобразованная версия требует отдельной host strategy.
Set-Cookie остаются
отдельными полями; application cookies и Vary/CORS dimensions сохраняются.
Cookie domain/path/name, SSO и storage key — правила приложения.

Нельзя вызвать старый `consumeHeaders(req,resp,...)` с настоящим response из
callback: он обойдёт coordinator и sanitizer. Перенесите изменения в editor/patch,
а данные Servlet — в подготовленный immutable контекст.

Для I/O после получения upstream headers есть `responseHeadersAsync`; до отправки
запроса — `requestHeadersAsync`. Функция возвращает `CompletionStage<ProxyHeaderPatch>`
быстро, storage работает на bounded executor приложения. Patch применяет только
безопасные header операции; он не содержит Servlet, editor или network request.
Deadline включает ожидание stage; после отмены поздний patch не применяется.

Это позволяет адаптировать `ExternalStorageCookieInterceptor`, сохраняя загрузку
и сохранение cookies вне Jetty callbacks. Сервис хранения, его атомарность и ошибки
остаются частью приложения. Shared `HttpCookieStore` намеренно пустой и не заменяет
пользовательское cookie storage.

## Header/body link rewriting

Общие правила из example можно подключить без Netty `ByteBuf`:

```java
var settings = new LinkRewriteSettings(backendBase, frontendBase,
        8L * 1024 * 1024, 12L * 1024 * 1024);
var plan = ProxyRequestPlan.builder(target)
        .customizer(new HeaderRewriteCustomizer(settings, frontendRequestUri, true))
        .responseBody(LinkRewritePolicy.legacyCompatible(settings))
        .build();
```

Для точного selector портала используйте `legacyCompatible(settings)`: raw target
path до первого `;`, без query и декодирования `%XX`, распознаёт `.css`/`.wcs`
без учёта регистра даже при отсутствующем или binary MIME. MIME subtype `css`
также выбирается; остальные ответы выбираются как `text/html`, кроме прежних
case-sensitive image/font исключений `.png`, `.jpg`, `.woff`, `.ttf`, `.jpeg`,
`.gif` в пути. CSS/WCS имеют приоритет. Реальные `Content-Type` и charset
сохраняются: например, WCS с octet-stream выполняет literal URL mapping,
а не получает выдуманный `text/css`.

`new LinkRewritePolicy(settings)` оставляет отдельный безопасный MIME selector
HTML/CSS/JavaScript. В точном legacy selector JavaScript MIME сам по себе не
выбирается: прежний `isBinaryResponse()` тоже его пропускал.

Link policy обрабатывает literal backend-base ссылки в выбранных ответах и
конкретные HTML href/base/form rules legacy. Это не полноценный HTML/CSS parser;
регистр и unusual form-action правила сохранены намеренно. Собственные rewriters
и изменения бизнес-семантики нужно подключить и проверить отдельно.

Без body policy ответ streaming. В выбранной ветке headers задерживаются, input
собирается в bounded direct slabs, CPU-преобразование идёт на ограниченном worker.
Output может заимствовать read-only input slices; новые вставки не требуют pooled
cleanup. UTF-8/ASCII-compatible single-byte charsets используют этот byte path;
для других charsets есть bounded String fallback с полными heap-копиями.

Engine имеет отдельный `ProxyRewriteOptions`: defaults 2 workers / 8 queued tasks /
128 МиБ. Spring binding — `jetty.proxy.rewrite.workers`, `max-queued`,
`max-buffered-bytes`; workers/queue дополнительно ограничиваются admission.
До создания collector резервируется сумма объявленных `maxInputBytes +
maxOutputBytes + chunkBytes`; overflow или нехватка бюджета дают `503`.
Резерв возвращается однократно после worker/write cleanup, включая отмену.
Это консервативная reservation, а не сумма уже полученных байтов: при лимитах
8 + 12 МиБ и chunk 64 КиБ один выбранный обмен резервирует 20,0625 МиБ.

Мелкие соседние готовые output spans меньше 1024 байт объединяются максимум до
`chunkBytes` и 1024 spans за запись. Одинокий маленький span и исходно крупный span
с его хвостом остаются borrowed; scratch удерживается только до write callback.
Max input/output и 65 536 segments ограничивают полезные данные, не всю память JVM.
Queued rewrite при cancellation удаляется; уже начавший worker держит input до
возврата. Произвольный пользовательский Java-код нельзя принудительно остановить.

Сжатые/bodyless/range/no-transform ответы обходят преобразование. При rewrite
меняются длина и validators; потенциальные HEAD/304 не публикуют исходные metadata.
Для GET/HEAD без effective outgoing Range origin-specific conditional validators
удаляются; wildcard `If-None-Match: *` и write preconditions сохраняются.
`206` остаётся upstream representation: ranges преобразованного тела не реализованы.
При строгом согласовании Range отключайте его, не включайте policy или разделяйте URL.

## Ошибки и completion

По умолчанию до commit network/protocol failure даёт `502`, основной deadline —
`504`, overload/stop/cancellation — `503`, application policy failure — `500`.
`errorPolicy()` может вернуть `ProxyErrorResponse` с нужным статусом и текстом,
например для сохранения legacy `408/500`. Тело ограничено 64 КиБ; callback быстрый
и не пишет Servlet. Error write ограничен отдельным watchdog `connect-timeout`,
поэтому после основного deadline допускается этот дополнительный срок дописывания.
Preflight rejections остаются пустыми, а после commit ответ можно только оборвать.

До commit application cookies/CORS/security headers восстанавливаются из снимка.
Body metadata очищается, ошибки получают `no-store`. Broken wrappers и плохая
подготовка не должны оставлять async timeout=0 навсегда; owned context завершается
или его канал обрывается. Исходная причина доступна в результате.

`ProxyCall.completion()` публикуется после engine cleanup; status описывает
переданный HTTP-код, failure — сбой доставки тела. UploadedBytes — прочитанное из
Servlet, downloadedBytes — успешные write callbacks. Handle отсоединяет Exchange.
Не вызывайте старый lifecycle с сохранённым Servlet после его recycle: подготовьте
immutable данные метрик/tracing, используйте быстрый callback либо `*Async`.

## Существующий кэш ответов

Исправленный `ContentCachingResponse` из `examples/proxy/caching` передаёт реальные
Servlet readiness/listener и ограничивает накопление: 8 МиБ на запись, общий
бюджет 128 МиБ с резервом `2 × maxCaptureBytes` для сегментов и финального массива.
Overflow или нехватка бюджета отключают кэширование, сохраняя streaming.
`finishCapture(true)` вызывается после успешного `ProxyCall.completion()` на
ограниченном executor публикации; ошибка/отмена вызывают `discardCapture()`.
Callback не читает уже завершённый Servlet.

Готовые записи ограничены по весу в `SharedResponseCache`; попадания обслуживает
`CachedResponseWriter` с настоящим `WriteListener`. Эти прикладные классы не входят
в JAR библиотеки. Policy общего кэша допускает только явно выбранные публичные
GET и ответы с подтверждённой свежестью; персональные и условные запросы обходят
его. Полные тела по-прежнему занимают heap, поэтому cache budgets и число активных
cache hits настраиваются отдельно от streaming/rewrite admission.

Подключение, публикация и shutdown: [инструкция кэша](../examples/proxy/caching/README.md).
Тесты host компилируют именно эти исходники и проверяют их с новым Jetty engine.
Старый Netty writer factory продолжает выбирать blocking writer для такой обёртки;
его путь записи этим изменением не переносится.

## Отдельный loader вместо повторной отправки исходного запроса

Для `HttpProxy.load(path,...)` есть bounded fetch, но application-facing facade
должен быть отдельным от HTTP proxy handler. В host-примере это
`ProxyResourceLoader` / `JettyResourceLoader`.

Loader строит trusted target из переданного resource path и делает отдельный GET.
Он не копирует исходный POST/PUT body. Используются явные prepared headers и
wire/decoded/text budgets. Charset берётся из Content-Type; gzip/deflate decode
включается явно. Полученная строка создаёт heap-копию; старые Content-Length,
Content-Encoding и digests нельзя переносить в новый UTF-8 response.

В host-примере общий `HttpProxyFilter` принимает единственный `path` только из
query, применяет maintenance/backend/access guards и сохраняет готовый route в
attribute для MVC. Form/POST body не выбирает include, повторная маршрутизация в
controller не выполняется. Этот full text loader превращает только origin `200`
в собственный UTF-8 `200`. Остальные origin статусы, включая `206`, redirect и
`401`, дают собственный `502` без cookies/challenge/Location исходного ответа.
Core fetch продолжает возвращать исходные HTTP статусы; это явная политика host.

Синхронный `fetchBlocking()` выполняется на вызывающем application/Servlet thread,
не на Jetty I/O или владельце Exchange. Async цепочке нужен `fetch()` и
`ProxyFetchCall`. Ошибки HTTP остаются ответами; cancellation/deadline/лимиты —
exceptional completion. Interrupt caller отменяет его загрузку.

Fetch capacity отдельная: defaults 8 concurrent / 2 decode workers / 8 queue.
Spring binding — `jetty.proxy.fetch`; его concurrency/queue ограничиваются также
transport maxConcurrent, но semaphore со streaming не разделяется. Полные тела,
декодирование и строка требуют большего budget, чем один streaming chunk.

## Профили TLS и оставшаяся логика приложения

`ProxyTlsProfile` задаёт trust/key stores, alias и протоколы до client start.
Default сохраняет проверку сертификата и hostname. Одна TLS identity соответствует
одному shared client; разные identities и ротация требуют отдельных qualified
clients/engines и версии конфигурации в application key.

Library не добавляет Security/CSRF, response cache, registry, maintenance views,
Resilience4j или retries. Эти механизмы сохраняются в host вокруг выбранного handler.
Feature toggle остаётся будущим selector на этой границе. Не создавайте client
на каждый запрос и не меняйте SSL identity активного client.

Текущие исправления и проверки: [FIXES.md](../jetty-proxy/FIXES.md) и
[fix-verification.json](../jetty-proxy/fix-verification.json). История выделения
библиотек — [IMPLEMENTATION.md](../jetty-proxy/IMPLEMENTATION.md), предыдущий совет —
[USAGE_REVIEW.md](../jetty-proxy/USAGE_REVIEW.md).
Отчёты [INTEGRATION_REVIEW.md](INTEGRATION_REVIEW.md) и
[COUNCIL_REVIEW.md](COUNCIL_REVIEW.md) фиксируют предыдущую standalone структуру.
Замер `filter-integration-performance.json` также исторический (`e078071`),
до новых библиотек, loader и policies; он не измеряет производительность портала.
