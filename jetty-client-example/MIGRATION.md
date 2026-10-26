# Подключение Jetty Client к существующей логике прокси

Точка входа текущего приложения — `examples/proxy/HttpProxyFilter.java`. Новый
модуль предоставляет Filter, общий streaming engine и настройки одного обмена,
чтобы бизнес-маршрутизация оставалась перед транспортом. Demo Filter зарегистрирован
на `/*`, а default planner выбирает `/proxy` и `/proxy/*`, снимает этот префикс
и добавляет base path из `example.proxy.upstream`. В приложении planner может получить готовый target
из выбранного backend, без привязки engine к `/proxy`.

Jetty Client убирает переход Netty pipeline → `ByteBuf` → Jetty writer для передачи
ответа без изменения тела. Upstream `Content.Chunk` передаётся Jetty с сохранением
владения до callback записи. При Servlet wrappers используется переносимый
Servlet I/O: wrappers сохраняются, поэтому такой путь включает копирование порции.
Полное отсутствие копий и ускорение относительно текущего приложения не заявляются.

## Соответствие существующим классам

| Текущая логика | Подключение нового транспорта |
| --- | --- |
| `HttpProxyFilter.shouldServeLocally()` | Оставить перед передачей транспорту; local request продолжает Filter chain |
| `pageOnMaintenance()` | Выполнить до async и чтения тела |
| `HttpProxy.proxyRequest()` | Передать выбранному executor prepared plan; для нового пути не создавать второй async |
| `ProxyRequestExecutorLookup.forRequest()` | Будущая точка выбора старого или нового executor для одного запроса |
| `NettyProxyRequestExecutor.execute()` | Выбрать backend, проверить forbidden URI и подготовить plan до `forward()` |
| Backend в attribute/session | Считать в исходном Servlet thread и снять необходимые значения; Spring Security модуль не добавляет |
| `cutBackendUri()` + `fullBackendUrl()` | Сформировать полный raw target URI в planner |
| `URIOverride.getTimeout()` / `Backend.getTimeout()` | `ProxyRequestPlan.Builder.timeout()`; отсутствие override оставляет общий `exchange-timeout` |
| `HeaderProcessor.collectHeaders()` + `PortalHeaderContributor` | Перенести правила в global/per-request `ProxyHeaderCustomizer`; требуемые внешние данные получить заранее |
| `HeaderProcessor.consumeHeaders()` | Response customizer до commit: cookies, `Location`, cache headers |
| `ResponseBodyRewriter` / `ByteRewriteChain` | Opt-in `ProxyResponseBodyPolicy` с ограничением входа/выхода и worker; алгоритмы требуют переноса с `ByteBuf` |
| `RequestCallback`, lifecycle, metrics/tracing | `ProxyCall.completion()` с результатом без обращения к Servlet |
| `ChannelManager`, `Bootstrap`, Netty transport | Общий Jetty `HttpClient`; соединениями управляет клиент |
| `HttpProxy.load(path, request)` → `String` | Сохранить старую реализацию до отдельного buffered fetch adapter |

Первичная адаптация и baseline-проверки: [INTEGRATION_REVIEW.md](INTEGRATION_REVIEW.md).
Повторный совет пяти экспертов и последующие исправления:
[COUNCIL_REVIEW.md](COUNCIL_REVIEW.md).

Исходники `examples/proxy` служат примером приложения и не входят в source sets
этого модуля. Модуль не объявляет их классы и не переносит зависимости портала.

## Planner и однократный выбор реализации

`ProxyRequestPlanner.prepare(HttpServletRequest)` выполняется в исходном потоке
Filter. Он возвращает immutable `ProxyRequestPlan` или `null` для продолжения
локальной цепочки. Здесь читаются backend attributes, данные сессии, principal,
request id и настройки маршрута. Planner не должен читать тело: никаких
`getParameter*()`, `getParts()` и предварительного `getInputStream()`.

План содержит target, optional timeout, per-request header customizers и optional
body policy. Target допускает `http`/`https`, host, допустимый port, raw path и
query. User info и fragment запрещены. Path/query нужно собирать из raw компонентов:
`URI.resolve()` с начальным `//` может сменить authority, а конструктор из
декодированных компонентов повторно кодирует `%`. Адрес выбирается конфигурацией
backend; прямую передачу клиентского URL в target planner не предполагает.

Контракт транспорта:

```java
ProxyCall forward(HttpServletRequest request,
                  HttpServletResponse response,
                  AsyncContext supplied,
                  ProxyRequestPlan plan);
```

`ProxyExchangeExecutor` — общая граница для вызова. `JettyProxyEngine` реализует её,
а `JettyProxyFilter(executor, planner)` связывает с Servlet Filter. Пример оставляет
возможность вставить выбор executor на этой границе; feature toggle, property и
выбор старой реализации не реализованы.

Для будущего toggle правило одно: выбрать реализацию **один раз на запрос**, до
начала передачи тела, затем полностью завершить обмен ею же. Старый adapter может
вызвать существующий Netty executor, новый — Jetty engine. При ошибке нового пути
нельзя отправлять тот же POST/PUT старым транспортом: тело уже могло уйти backend.
Изменение toggle влияет на последующие запросы и не меняет активные обмены.

`JettyProxyServlet` остаётся дополнительным адаптером и использует тот же engine.
Servlet, созданный через `(engine, upstream)`, не закрывает общий engine.
В Spring lifecycle engine принадлежит конфигурации, а `HttpClient` останавливается
после него. Не создавайте `HttpClient` или engine для каждого запроса.
При динамической смене backend origins положительный
`example.proxy.destination-idle-timeout` (по умолчанию `60s`) удаляет пустые
простаивающие destinations клиента. Socket `idle-timeout` закрывает соединения,
но сам по себе не ограничивает срок хранения записей назначения. Данный TTL
не задаёт жёсткий предел числа одновременно известных origins; их выбирает
доверенная конфигурация приложения.

## Встраивание в текущий async-код

Legacy `HttpProxy.proxyRequest()` уже вызывает `startAsync()`. Можно перенести
начало async внутрь нового engine: передать `supplied = null`, после чего engine
вызовет `request.startAsync(request, response)`. Альтернатива для адаптации текущего
`ProxyRequestExecutor.execute()` — передать существующий `AsyncContext`.

При втором варианте текущую строку `req.startAsync()` нужно заменить на
`req.startAsync(req, resp)`. Это сохраняет именно те request/response wrappers,
которые получил Filter. Переданный async должен принадлежать этой паре объектов;
context другой пары отклоняется и не завершается engine: его lifecycle
остаётся у первоначального владельца. Начатый async без передачи его в `forward()`
считается ошибкой интеграции, а не сигналом начинать async повторно.
Оба варианта `forward()` вызываются на исходном Servlet dispatch thread до его
возврата. Это позволяет зарегистрировать lifecycle listener до возможного
завершения и переработки запроса контейнером. Вызов engine из позднего worker
после возврата dispatch не является поддерживаемым способом передачи владения.

Если accessor прикладной request/response wrapper выбрасывает runtime exception
при подготовке обмена, engine возвращает однократный результат `500` с причиной;
валидный supplied async завершается, а admission не остаётся занятым. При
отсутствующем executor клиента возвращается `503` до запуска нового async.
Чужой context при этом по-прежнему не завершается: его принадлежность
проверяется раньше обычных отказов.

Следующий adapter использует только публичные API модуля. Метод `execute()`
вызывается из собственного Filter или приложения; это не реализация интерфейса
из пакета портала. Target и request id должны быть подготовлены вызывающей логикой.

```java
import dev.example.jettyclient.proxy.ProxyCall;
import dev.example.jettyclient.proxy.ProxyExchangeExecutor;
import dev.example.jettyclient.proxy.ProxyHeaderCustomizer;
import dev.example.jettyclient.proxy.ProxyHeaderEditor;
import dev.example.jettyclient.proxy.ProxyRequestMetadata;
import dev.example.jettyclient.proxy.ProxyRequestPlan;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;

final class PreparedProxyAdapter {
    private final ProxyExchangeExecutor executor;

    PreparedProxyAdapter(ProxyExchangeExecutor executor) {
        this.executor = executor;
    }

    ProxyCall execute(HttpServletRequest request, HttpServletResponse response,
                      AsyncContext supplied, URI target, String requestId) {
        // requestId — уже подготовленный immutable снимок, не обращение к Servlet.
        var plan = ProxyRequestPlan.builder(target)
                .timeout(Duration.ofSeconds(15))
                .customizer(new ProxyHeaderCustomizer() {
                    @Override
                    public void customizeRequest(ProxyRequestMetadata metadata,
                                                 ProxyHeaderEditor headers) {
                        if (requestId != null) headers.set("X-Request-ID", requestId);
                    }
                })
                .build();
        return executor.forward(request, response, supplied, plan);
    }
}
```

Для старого `ProxyRequestExecutor` это означает adapter от его аргументов к
prepared plan и результату. `ProxiedRequestLifecycle` хранит Servlet request;
его нельзя автоматически переносить в поздний completion callback. Снимите
backend name, target, request id и tracing context заранее, а метрики завершайте
по `ProxyExchangeResult`. Синхронный template include через `HttpProxy.load()`
требует собственного adapter: неблокирующий response streaming не возвращает строку.

## Заголовки, cookies и контекст пользователя

Глобальные `ProxyHeaderCustomizer` beans выполняются по `@Order`, затем вызываются
customizers плана в порядке добавления. Они получают редактор отдельной копии
headers только на время обработки. Callback работает на владельце обмена и не должен обращаться
к БД/сети, блокироваться или читать Servlet. Не сохраняйте editor для позднего
использования и не обращайтесь к `SecurityContextHolder`/MDC как к исходному
Servlet thread: захватывайте необходимые immutable значения в planner.

Например, backend cookie name/path, frontend prefix, user id и готовые request
cookies можно захватить в per-request customizer. Для `Location` используйте
`headers.values("Location")`, подготовленное соответствие frontend/backend URL
и `set()`/`add()`. Для cookies обрабатывайте отдельные строки `Set-Cookie`,
а не разделённый запятыми список. `add()` сохраняет все значения; proxy также
добавляет upstream `Set-Cookie` к cookies, которые выставили предыдущие Filters.
`Vary` upstream дополняет имеющиеся измерения кеширования приложения, например
`Origin` и `Access-Control-Request-Headers` из CORS-фильтра. Перед handoff engine
фиксирует исходные headers ответа. При локальной ошибке до commit после reset
восстанавливаются именно эти application headers: cookies, CORS и security
headers сохраняются, upstream metadata повреждённого ответа не восстанавливается.
Затем локальный ответ с ошибкой очищается от metadata прежнего тела, включая
encoding, ranges и validators, и получает `Cache-Control: no-store`. Даже если
приложение заранее выставило публичное кеширование или gzip для успешного ответа,
эти поля не переносятся на пустое тело ошибки.
Request-scoped `Basic`/`Bearer` допустимы; `NTLM`/`Negotiate` отклоняются, поскольку
их идентичность может оставаться на общем upstream соединении.

Существующие `CookieInterceptor.cookieFromBackend(req, resp, ...)` нельзя вызвать
без изменений из нового безопасного API: оно не предоставляет Servlet. Вынесите
правила преобразования в функцию от immutable контекста и списка cookie strings.
Если `CookieStorageService` требует внешнего I/O, подготовьте данные до передачи
engine или организуйте отдельный асинхронный этап вне callbacks. Добавление общего
`HttpCookieStore` не заменяет серверное хранилище cookies пользователя: store
намеренно пустой, чтобы данные не смешивались между запросами.

Фрейминг, hop-by-hop и описание тела контролирует engine. Editor не позволяет
изменять `Host`, `Content-Length`, `Transfer-Encoding`, `Content-Type`,
`Content-Encoding`, `Content-Range` и остальные защищённые поля. Новые прикладные
заголовки добавляются без изменения транспортного алгоритма.

## Ограниченное преобразование тела

Без `responseBody()` plan сохраняет прежний streaming путь: ответ не копится
целиком. Policy выбирает преобразование по `ProxyRequestMetadata` и финальным
`ProxyResponseMetadata`; возврат `null` сохраняет streaming. Engine разрешает
его для полного ответа с телом без content coding и range-семантики.
Policy может выбирать разные статусы, включая `201` и `404`.
Сжатые ответы, HEAD, частичные ответы и `Cache-Control: no-transform` в запросе
или ответе сохраняются без преобразования.
Parser `Cache-Control` разделяет директивы с учётом quoted values:
например, `no-transform` внутри quoted значения extension directive не запрещает
rewrite само по себе.
Прикладная policy сама ограничивает подходящие MIME types и статусы;
двоичный ответ сам по себе не запрещает явно выбранное преобразование.

HEAD/304 требуют отдельного согласования metadata: длина и validators исходного
upstream могут не описывать тело, которое вернул бы GET после rewrite. Для
потенциально преобразуемого ответа engine консервативно удаляет эти metadata,
включая `Content-Length`, вместо публикации неверной длины/validators. Policy
может фактически оставить конкретный MIME streaming; такая консервативная
обработка всё равно допустима. Для потенциально преобразуемых GET/HEAD без
исходящего `Range` не-wildcard
`If-None-Match` и `If-Modified-Since` удаляются перед отправкой upstream, чтобы
получить полное тело. Wildcard `If-None-Match: *` и preconditions записывающих
методов сохраняются; engine не подменяет прикладные проверки существования и
конкурентного обновления ресурса.

Исходный `206` и `Content-Range` остаются streaming без rewrite. Это сохраняет
байты upstream, но не реализует диапазоны преобразованного представления. Если
клиент должен кешировать и запрашивать ranges именно преобразованного тела,
приложению нужно отключить `Range`, убрать policy для такого маршрута либо
разделить URL исходного и преобразованного представлений. Нельзя склеивать ranges
исходного upstream с ранее преобразованным полным ответом как одну representation.

Для выбранного ответа engine задерживает headers, собирает данные в pooled
direct slabs с пределом `maxInputBytes` и не более 65 536 компонентов, затем
выполняет rewriter на ограниченном worker executor: 2 потока, очередь
`max-concurrent` задач (по умолчанию 128). `maxOutputBytes` ограничивает
возвращённый результат; output также допускает не больше 65 536 сегментов,
что проверяется до создания списка views. Эти пределы относятся
к данным тела; объектная metadata, TLS, parser и пользовательские аллокации
rewriter потребляют дополнительную память. Слишком большой output не становится
безопасным только из-за проверки после возврата: сам алгоритм должен соблюдать
установленный бюджет при создании вставок.

Результат — `ProxyResponseBody`, список read-only срезов `ByteBuffer` с известной
длиной. `buffers()` возвращает свежие views; движения position не меняют input.
Возвращать заимствованные срезы input разрешено: engine удерживает память до
окончания worker и записи output. Новые вставки должны иметь обычную memory
lifecycle, без собственного pooled cleanup. Не release вход и не сохраняйте
его срезы для использования после обмена.

Ниже реальный пример opt-in вставки префикса к `text/plain`; он не переписывает
HTML/CSS и не декодирует весь input в строку:

```java
import dev.example.jettyclient.proxy.ProxyResponseBody;
import dev.example.jettyclient.proxy.ProxyResponseBodyPolicy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

ProxyResponseBodyPolicy prefixText = (request, response) -> {
    boolean plain = response.headerValues("Content-Type").stream()
            .anyMatch(value -> value.split(";", 2)[0].trim().equalsIgnoreCase("text/plain"));
    if (!plain) return null;
    return new ProxyResponseBodyPolicy.Selection(8L * 1024 * 1024,
            8L * 1024 * 1024 + 64, (metadata, input) -> {
                var output = new ArrayList<ByteBuffer>();
                output.add(ByteBuffer.wrap("proxied: ".getBytes(StandardCharsets.UTF_8)));
                output.addAll(input.buffers());
                return ProxyResponseBody.of(output);
            });
};
// В planner: ProxyRequestPlan.builder(target).responseBody(prefixText).build()
```

Для переноса `ByteRewriteChain` используйте тот же принцип, что в текущих
`ByteSplice`: неизменённые участки остаются срезами исходных сегментов, замены
становятся отдельными вставками. Нужно перенести поиск и сборку с Netty `ByteBuf`
на сегменты `ByteBuffer`; готовые HTML/CSS/link algorithms сюда не скопированы.
Текущий модуль не зависит от Netty. При преобразовании engine устанавливает новую
длину и удаляет прежние `ETag`, `Content-MD5`, `Digest`, `Content-Digest`,
`Repr-Digest`, `Last-Modified` и `Accept-Ranges`; rewriter возвращает только тело.
Согласование cache, charset и конкретных правил изменения ссылок остаётся частью
алгоритма и настроек приложения.

Rewriter выполняет ограниченную CPU-работу, не сетевые вызовы и не бесконечный
цикл. При timeout/cancellation queued-задача удаляется из очереди без запуска
rewriter; её input освобождается, не дожидаясь других занятых workers. Если
worker уже начал преобразование, произвольный Java-код нельзя принудительно
прервать: input остаётся удержанным до его выхода, даже если клиент уже получил
ошибку. Заполненная очередь не превращается в неограниченное накопление задач.

## Завершение, ошибки и остающиеся задачи интеграции

`ProxyCall.completion()` публикует `ProxyExchangeResult`: status, failure,
uploadedBytes, downloadedBytes, elapsed и `success()`. HTTP `404` backend может
успешно передаться (`success() == true`); транспортная ошибка после `200` headers
сохраняет `status == 200` и даёт failure. UploadedBytes — прочитанное из Servlet,
без гарантии доставки backend; downloadedBytes — успешно завершённые записи
downstream. Completion выполняется после возврата принадлежащих обмену буферов,
а не как обещание физического закрытия TCP/TLS-сокета. Servlet уже мог быть
переработан контейнером — callback не читает request/response и не пишет ответ.
Обработчик должен быть лёгким; для более дорогих метрик используйте
`whenCompleteAsync(..., applicationBoundedExecutor)` со своим ограниченным executor.
Завершённый `ProxyCall` отсоединяет Exchange, поэтому хранение handle для метрик
само по себе не удерживает request/response и буферы обмена. Прикладные callbacks
и exception objects остаются обычными Java-объектами: не захватывайте в них Servlet.

`cancel(cause)` отменяет только этот обмен и безопасен после его завершения.
Deadline включает очередь, upload, получение ответа, ожидание worker и download.
Per-plan timeout положительный, не меньше 1 мс и помещается в наносекундный `long`.
Значение `0`, которое legacy использует как отключённый timeout, переносить
буквально нельзя: выберите общий или явный положительный предел.

| Событие до commit | Новый ответ |
| --- | --- |
| Upstream/network/protocol failure | `502` |
| Общий deadline | `504` |
| Заполнен admission/worker queue, остановка, cancellation | `503` |
| Ошибка прикладного header/body расширения | `500` |
| Runtime exception при подготовке Servlet wrapper | `500` с исходной причиной в результате |

Legacy `ProxyCommand` оформляет общий сбой как `500`, timeout/bulkhead как `408`
с текстом ошибки. Эти статусы и fallback body автоматически не перенесены.
После commit engine обрывает повреждённый ответ вместо записи успешного EOF;
заменить уже отправленный HTTP status невозможно. Без нового I/O reset GET до
первых headers может обнаружиться только по deadline.

Перед включением в приложение остаётся согласовать собственные response cache,
Resilience4j/circuit breaker, maintenance/error views, профили TLS backend,
обновление backend конфигурации и маршрутов, Spring Security/сессии и правила
HTML/CSS rewriting. Точки подключения транспорта и bounded body policy готовы,
но эти бизнес-механизмы модуль самостоятельно не включает. Multipart должен
доходить исходным потоком: прежняя пересборка уже разобранных form parameters
не является частью нового пути.

В demo глобально отключён MVC `FormContentFilter`. Поэтому локальные
PUT/PATCH/DELETE с form-urlencoded телом также не получают его разбора в
`@RequestParam`. При переносе в приложение настройте порядок или область
действия body-reading filters: локальные формы должны сохранить привычную
обработку, а проксируемый запрос — попасть в engine до чтения параметров/parts.

Замеры [strict-review-performance.json](strict-review-performance.json) относятся
к предыдущей версии Servlet до этой адаптации. Их нельзя использовать как
сравнение нового пути с реальным приложением и его обработкой cookies/ссылок.
[Короткий контроль первой адаптации](filter-integration-performance.json) сравнивает Servlet
и Filter/engine: 159,96 → 176,83 rps для GET 8 МиБ, 6521 запрос с прогревом
без HTTP/size/SHA ошибок. По два коротких прогона на вариант при общем CPU
с генератором/upstream не доказывают устойчивое ускорение или production capacity.
Этот baseline предшествует исправлениям повторного совета; их проверки и
актуальные ограничения указаны в [COUNCIL_REVIEW.md](COUNCIL_REVIEW.md).
