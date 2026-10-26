# Jetty Proxy: подключаемая библиотека

`jetty-proxy` — обычный Java 21 JAR с Servlet async proxy и отдельным bounded
resource fetch на Jetty **12.1.12**. Spring, Boot application, Netty, маршрутов
и `application.yml` в библиотеке нет. Servlet API **6.1** предоставляет host.
Native Servlet I/O рассчитан на Jetty EE11; наличие переносимого writer не
является обещанием поддержки произвольного Servlet-контейнера.

Для Boot-приложения есть необязательный [Spring adapter](../jetty-proxy-spring/README.md).
[Standalone demo](../jetty-client-example/README.md) и
[host-проверка подключения](../jetty-proxy-integration-example/README.md)
зависят от этих библиотек, а не наоборот. Реальный портал недоступен: example
`examples/proxy/` задаёт требования, host доказывает подключение отдельных JAR,
но не заменяет проверку в самом приложении.

## Подключение и сборка

В этом репозитории:

```groovy
dependencies {
    implementation project(':jetty-proxy')
}
```

Нужны JDK 21 и Gradle 8.14.3. В подготовленной облачной среде:

```bash
/workspace/.setup/gradle :jetty-proxy:build
```

Результат — `build/libs/jetty-proxy-1.0.0.jar` и sources JAR. Классы находятся
в корне обычного JAR, без `BOOT-INF`. Один скопированный JAR не включает Jetty
dependencies: используйте Gradle/Maven metadata и Servlet API своего контейнера.

`maven-publish` настроен на локальный verification repository:

```bash
/workspace/.setup/gradle :jetty-proxy:publishMavenJavaPublicationToVerificationRepository
```

Координаты — `dev.example:jetty-proxy:1.0.0`, repository — `build/repository`.
Это локальная публикация для проверки, не публикация в Maven Central.
В Maven POM Servlet API имеет scope `provided`; в Gradle runtime dependencies
его также нет. Принимающее приложение явно предоставляет совместимый Servlet API.

## Жизненный цикл и точка входа

Один client и его пользователи создаются на срок жизни приложения, не запроса:

```java
var properties = JettyProxyProperties.defaults();
var client = JettyProxyClientFactory.createStarted(properties);
var proxy = new JettyProxyEngine(client, properties);
var fetch = new ProxyFetchClient(client, properties);
```

Engine и fetch принимают только клиента, созданного `JettyProxyClientFactory`:
обычный `new HttpClient()` не сохраняет регистр extension HTTP methods.
Неподдерживаемый клиент отклоняется конструктором до создания workers.
Для настройки executor, имени и сетевых параметров используйте callback до start:

```java
var client = JettyProxyClientFactory.createStarted(properties, tlsProfile, configured -> {
    configured.setName("portal-backend-v2");
});
```

Callback вызывается один раз и не должен запускать/останавливать client. После него
фабрика восстанавливает обязательные proxy controls: TLS profile, проверку hostname,
отключение shared cookies, автоматических auth/redirect/decode и ограничения metadata.
При исключении callback или нарушении lifecycle фабрика освобождает свой client.

При shutdown закройте `proxy` и `fetch`, затем вызовите `client.stop()`.
Оба компонента отменяют свои обмены, но не останавливают переданный shared client.
Их `close()` не может принудительно закончить произвольный пользовательский CPU-код.

В существующем Filter/Servlet вызывается общий контракт:

```java
var plan = ProxyRequestPlan.builder(target)
        .timeout(Duration.ofSeconds(20))
        .build();
ProxyCall call = proxy.forward(request, response, null, plan);
```

`target` — полный raw HTTP(S) URI из доверенной backend-конфигурации. Engine
не выбирает host по клиентскому URL. `upstream` в core properties сохранён для
дополнительного `JettyProxyServlet`; обычный engine и fetch используют явный target.

`forward()` вызывается на исходном Servlet dispatch thread. При `supplied == null`
он начинает `startAsync(request,response)`. Для существующего async передайте
контекст, созданный именно для этой пары wrappers. После handoff никто другой
не пишет response, не продолжает chain и не повторяет запрос другим транспортом.
Библиотека не включает feature toggle; место выбора — перед этим вызовом.

`ProxyCall.completion()` возвращает однократный `ProxyExchangeResult` после cleanup.
Он содержит status, failure, elapsed и byte counts. Upstream `404` может быть
успешно передан; после commit ошибка тела сохраняет HTTP status и добавляет failure.
UploadedBytes означает прочитанное из Servlet, не подтверждение доставки backend;
downloadedBytes — успешные write callbacks. Завершённый handle отсоединяет Exchange.
Callbacks используют подготовленные данные, а не переработанный Servlet; дорогую
работу выполняйте через `*Async` на executor приложения. `cancel(cause)` отменяет
только этот обмен.

## Заголовки и прикладной I/O

`ProxyHeaderCustomizer` редактирует отдельную копию через `ProxyHeaderEditor`.
Глобальные hooks выполняются первыми, затем hooks плана. Они быстрые и не читают
Servlet, сеть или БД. Host, framing и metadata непрозрачного тела защищены;
итоговые поля повторно очищаются от hop-by-hop и исходных `Connection` nominations.
Cookies пользователей не сохраняются в общем client store. `Set-Cookie` и `Vary`
дополняют исходные поля приложения.

Для внешнего cookie storage есть отдельные stages:

```java
var plan = ProxyRequestPlan.builder(target)
        .requestHeadersAsync(metadata -> CompletableFuture.supplyAsync(
                () -> ProxyHeaderPatch.builder().set("Cookie", preparedCookie).build(),
                applicationExecutor))
        .responseHeadersAsync((requestMetadata, responseMetadata) ->
                CompletableFuture.completedFuture(ProxyHeaderPatch.builder().build()))
        .build();
```

`preparedCookie`, `applicationExecutor` и сама storage-функция принадлежат
приложению. Stage factory возвращает быстро; blocking I/O выполняется на его
ограниченном executor. Стадия возвращает immutable patch, не редактор или Servlet.
Request stage заканчивается до upstream I/O, response stage — до финальных
downstream headers и body demand. Общий deadline включает ожидание; поздний patch
после отмены не применяется. Внешнее хранилище и его транзакции не реализованы.

`.errorPolicy(...)` позволяет вернуть `ProxyErrorResponse` для ошибки принятого
обмена до commit, например текстовый `408` вместо стандартного `504`.
Renderer быстрый, не пишет Servlet и не обращается к внешним ресурсам. Тело ошибки
ограничено **64 КиБ**; framing и `Cache-Control: no-store` задаёт engine.
У error write есть отдельный watchdog длительностью `connect-timeout`;
он ограничивает дописывание ошибки после завершения основного deadline.
Preflight rejections остаются пустыми. После commit допустим только abort,
а не fallback поверх уже отправленного ответа.

## Переписывание ссылок и тела

Без `responseBody()` ответ идёт streaming: одна запись в полёте, upstream demand
зависит от готовности downstream. JSON, multipart и binary остаются непрозрачными;
request body не читается через `getParameter*`/`getParts` и не пересобирается.

Готовые переносимые политики находятся в `proxy.policies`:

```java
var urls = new LinkRewriteSettings(backendBase, frontendBase,
        8L * 1024 * 1024, 12L * 1024 * 1024);
var plan = ProxyRequestPlan.builder(target)
        .customizer(new HeaderRewriteCustomizer(urls, frontendRequestUri, true))
        .responseBody(LinkRewritePolicy.legacyCompatible(urls))
        .build();
```

`legacyCompatible(urls)` явно включает selector из `examples/proxy`: `.css` и
`.wcs` в raw path выбранного target распознаются без учёта регистра, после удаления
суффикса от первого `;`; query не участвует и `%XX` не декодируются. Такие ресурсы
и MIME subtype `css` обрабатываются даже без `Content-Type` или при
`application/octet-stream`. Остальные ответы выбираются только как `text/html`,
с прежними case-sensitive исключениями `.png`, `.jpg`, `.woff`, `.ttf`, `.jpeg`,
`.gif` в пути. CSS/WCS проверяются раньше этих исключений. Это opt-in доверие
прикладному имени ресурса; фактические MIME и charset ответа не подменяются.

Без этого режима `new LinkRewritePolicy(urls)` сохраняет безопасный MIME selector
HTML/CSS/JavaScript: неизвестные и binary MIME остаются streaming даже у `.css` URL.
JavaScript MIME без CSS/WCS path выбирается этим default selector; точный legacy
selector его пропускает, как прежний `isBinaryResponse()`.

`HeaderRewriteCustomizer` сопоставляет `Origin`, `Referer`, `Location` и просит
`Accept-Encoding: identity` для rewrite, если клиент допускает identity.
При `identity;q=0` (или запрещающем wildcard без разрешённого identity) исходная
negotiation сохраняется, link policy выбирает passthrough. Для клиентов, которым
нужно сжатое преобразованное тело, требуется outbound compression strategy host.
Cookie domain/path/SSO остаются правилами
приложения. `LinkRewritePolicy` переносит общие literal/full-link и HTML href/base/
form правила из example; остальные выбранные текстовые ресурсы используют literal
mapping backend base, сохраняя свои MIME и charset.
Это сохранение конкретных legacy правил с учётом регистра, а не HTML/CSS parser.
Правила форм и нестандартные случаи нужно проверить на собственных страницах.

Выбранное тело накапливается в direct slabs с `maxInputBytes`; output ограничен
`maxOutputBytes` и 65 536 сегментами. Worker executor ограничен; queued rewrite
при отмене удаляется, running worker удерживает input до своего возврата.
Готовые соседние малые output-сегменты объединяются в порции до `chunkBytes`;
крупные срезы остаются borrowed. Дополнительный pooled buffer освобождается только
после write callback, включая отмену или ошибку.

Отдельный `ProxyRewriteOptions(workers, maxQueued, maxBufferedBytes)` имеет defaults
**2 / 8 / 128 МиБ**. Его можно передать четвёртым аргументом `JettyProxyEngine`.
До накопления каждого выбранного ответа engine резервирует
`maxInputBytes + maxOutputBytes + chunkBytes`; это консервативные declared limits,
даже если фактический ответ меньше. При нехватке бюджета или переполнении очереди
возвращается `503`; обычный streaming этот бюджет не расходует. Резерв удерживается
до завершения worker, записей и освобождения input. `reservedRewriteBytes()` и
`availableRewriteBytes()` показывают резерв, а не измеренный объём памяти.

UTF-8 и ASCII-compatible single-byte charsets используют срезы неизменённых
данных и отдельные вставки. Другие charsets, например UTF-16, используют явно
ограниченный String fallback с полными heap-копиями. Лимиты полезных данных не
задают точную границу RSS или всех аллокаций JVM.

Engine обходит сжатые, bodyless, range и `no-transform` ответы, учитывая quoted
Cache-Control values. После rewrite выставляются новая длина и очищаются старые
validators/digests. Потенциальный rewrite требует консервативной обработки HEAD/
conditional metadata. `206` остаётся исходным ответом: ranges преобразованной
версии не реализованы; отключите Range, уберите policy либо разделите URL.

## Bounded fetch и строковый loader

Это отдельная загрузка, не Servlet proxy и не чтение исходного POST/PUT:

```java
var request = ProxyFetchRequest.builder(resourceTarget)
        .headers(preparedHeaders)
        .timeout(Duration.ofSeconds(10))
        .maxWireBytes(2 * 1024 * 1024)
        .decodeContentEncoding(true)
        .maxDecodedBytes(8 * 1024 * 1024)
        .maxTextCharacters(4 * 1024 * 1024)
        .build();
ProxyFetchResult resource = fetch.fetchBlocking(request);
String text = resource.bodyAsString(StandardCharsets.UTF_8);
```

По умолчанию fetch делает отдельный GET с пустым телом. Передавайте только явно
подготовленные headers и доверенный target. Async-вариант — `fetch.fetch(request)`:
его `ProxyFetchCall` имеет `completion()` и `cancel(cause)`. HTTP ошибки остаются
обычными результатами; transport failure, deadline, cancellation, wire limit и
ошибки/лимит включённого Content-Encoding decoding дают exceptional completion.
Interrupt blocking caller отменяет только его загрузку.

Wire body, gzip/deflate decoded body и строка имеют отдельные пределы. Декодирование
Content-Encoding включается явно; charset берётся из Content-Type, иначе используется
переданный fallback (по умолчанию UTF-8). Body результата — самостоятельная heap-
память; строковое представление создаёт дополнительную копию. Не копируйте старые
Content-Encoding/Length/digests в новый ответ, повторно закодированный как UTF-8.
Charset и `maxTextCharacters` проверяются лениво в `bodyAsString()`: уже успешный
fetch может затем дать `IOException` при неизвестном charset или превышении числа
символов. Это не меняет завершённый `completion()`. Метрика строкового loader должна
охватывать и fetch, и преобразование в строку, фиксируя успех после обоих шагов.
`activeRequests()` учитывает владение transport/pooled input, а не сохранённые
результаты. Бюджет retained results, строк и кэша задаёт приложение отдельно от
лимита одновременных fetch.

`fetchBlocking` допустим на вызывающем application/Servlet thread, но не на Jetty
I/O, callback или владельце обмена. Для асинхронной цепочки используйте `fetch()`.
Предел fetch независим от streaming engine: `ProxyFetchOptions` defaults **8 / 2 / 8**
(concurrency / decoder threads / queue). Constructor с двумя аргументами ограничивает
defaults concurrency/queue ещё и `properties.maxConcurrent()`. Constructor с явными
options использует их буквально. Общий client/pool при этом разделяется.

## TLS и области ответственности

`JettyProxyClientFactory.createStarted(properties, profile)` применяет immutable
`ProxyTlsProfile` до start. Default — system trust, hostname verification,
TLS 1.2/1.3. Профиль поддерживает PKCS12/JKS trust/key stores и key alias:

```java
var profile = ProxyTlsProfile.builder("billing-tls-v2")
        .trustStore(trustStorePath, trustPassword)
        .keyStore(keyStorePath, keyPassword)
        .keyAlias("portal-client")
        .protocols("TLSv1.3", "TLSv1.2")
        .build();
```

Paths и char[] passwords предоставляет приложение. Разные TLS identities требуют
разных общих clients/engines; не меняйте identity активного client. Клиентский
keystore не становится неявным truststore: без отдельного trust store сохраняется
системное доверие. Профиль не
создаёт глобальный cache: версия конфигурации и ротация остаются у приложения.

`CONNECT`, upgrade, Expect, retries и HTTP/2 не поддерживаются. GET reset до
первых headers без нового I/O может обнаружиться только по deadline. Аутентификация,
CSRF, backend registry, maintenance, SSO/storage и response cache остаются в host.
Исходный портал этим модулем целиком не перенесён; готовность в production требует
проверок настоящего приложения. Детали реализации и проверки:
[IMPLEMENTATION.md](IMPLEMENTATION.md).

Повторная оценка сценариев, интеграции и производительности:
[USAGE_REVIEW.md](USAGE_REVIEW.md). В отчёте зафиксированы обнаруженные ошибки
cleanup и интеграции, ограничения rewrite и новые измерения текущих артефактов.
Исправления этих замечаний и актуальные результаты проверки:
[FIXES.md](FIXES.md), [fix-verification.json](fix-verification.json).
