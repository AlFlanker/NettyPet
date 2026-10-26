# Servlet + Jetty HttpClient

Самостоятельный пример на Java 21, Spring Boot **4.1.1** и Jetty **12.1.12** из BOM
Spring Boot. Встроенный Jetty принимает запросы, отдельный Jetty `HttpClient`
передаёт их HTTP(S) upstream по HTTP/1.1. Default planner использует фиксированный
адрес; приложение может подготовить target и настройки для каждого запроса.
Модуль не зависит от `app`, `proxy` и `command-support` и не содержит Spring Security.

Точки подключения к существующему приложению — `JettyProxyFilter`,
`ProxyRequestPlanner`, `ProxyExchangeExecutor` и `ProxyRequestPlan`.
[Гайд миграции из examples/proxy](MIGRATION.md) сопоставляет их с `HttpProxyFilter`,
executor lookup, обработчиками заголовков, cookies и переписчиками тела.
Feature toggle можно подключить на границе выбора executor; сам toggle здесь
не реализован. RequestProcessor, команды и Resilience4j модуль не включает.
Текущие изменения и проверки: [INTEGRATION_REVIEW.md](INTEGRATION_REVIEW.md).

[REVIEW.md](REVIEW.md), [STRICT_REVIEW.md](STRICT_REVIEW.md) и
[strict-review-performance.json](strict-review-performance.json) фиксируют
предыдущие этапы ревью Servlet-варианта. Указанные там тесты и измерения относятся
к соответствующим версиям. Новый короткий контроль Filter/engine находится
в [filter-integration-performance.json](filter-integration-performance.json):
159,96 → 176,83 rps для GET 8 МиБ, ошибок нет. Общий CPU с JMeter/upstream,
два коротких прогона на вариант; устойчивое ускорение этим не доказано.

Подробный [PDF-гайд по устройству модуля](docs/jetty-client-guide.pdf) показывает
порядок вызовов, upload/download, владение буферами, расширение заголовков,
ошибки и завершение обмена. Рядом находятся [текст гайда](docs/guide.md),
исходники диаграмм и [инструкция пересборки PDF](docs/README.md). Гайд обновлён
для Filter → planner → engine, request plan, completion и bounded rewrite.

## Запуск

Нужны JDK 21 и отдельно запущенный upstream. Все команды ниже выполняются из корня
репозитория. Используйте Gradle **8.14.3**: скрипт `gradlew` есть, но файлы
`gradle/wrapper` в текущем checkout отсутствуют. В подготовленной облачной среде
задайте `NETTYPET_GRADLE=/workspace/.setup/gradle`; локально с установленным Gradle —
`NETTYPET_GRADLE=gradle`.

```bash
NETTYPET_GRADLE=/workspace/.setup/gradle # В этой облачной среде; локально: gradle.
"$NETTYPET_GRADLE" :jetty-client-example:test :jetty-client-example:bootJar
PROXY_UPSTREAM=http://127.0.0.1:9000 java -jar jetty-client-example/build/libs/jetty-client-example-1.0.0.jar
```

Или запуск из исходников:

```bash
PROXY_UPSTREAM=http://127.0.0.1:9000 "$NETTYPET_GRADLE" :jetty-client-example:bootRun
```

По умолчанию приложение слушает **127.0.0.1:8081**, upstream — **127.0.0.1:9000**.
Параметры можно менять аргументами Spring Boot, например:

```bash
"$NETTYPET_GRADLE" :jetty-client-example:bootRun --args='--server.port=8082 --example.proxy.max-concurrent=32'
```

Проверка локального MVC-маршрута и проксирования:

```bash
curl -i 'http://127.0.0.1:8081/local'
curl -i 'http://127.0.0.1:8081/proxy'
curl --path-as-is -i 'http://127.0.0.1:8081/proxy/a%20b?x=%2F&x=2'
```

`/local` возвращает `local`. Остальные два запроса требуют соответствующего
маршрута на upstream; ответ `404` upstream также передаётся клиенту.

## Маршрутизация и тела

`JettyProxyFilter` зарегистрирован на **`/*`**, а default planner выбирает только
**`/proxy` и `/proxy/*`**. Filter поддерживает Servlet async для обычного REQUEST
dispatch. Planner выполняется до async на исходном потоке и возвращает plan;
`null` продолжает локальную Filter chain.
Остальные маршруты обслуживает обычный Spring MVC. При upstream
`http://127.0.0.1:9000/base`:

| Входящий запрос | Upstream |
| --- | --- |
| `/proxy` или `/proxy/` | `/base/` |
| `/proxy/a?x=1&x=2` | `/base/a?x=1&x=2` |
| `/proxying` | Не проксируется |

Путь и query принятого контейнером запроса используются без дополнительного
декодирования и нормализации. Уже записанные `%XX` не кодируются повторно.
Контейнер может отклонить неоднозначный URI до вызова Filter. `JettyProxyServlet`
остаётся дополнительным адаптером к общему engine; включать оба entry point для
одного и того же запроса не требуется.

По умолчанию тела передаются потоком, без сборки полного запроса или ответа
в памяти. Явно выбранное преобразование ответа использует отдельный ограниченный
путь, описанный ниже. JSON, бинарные данные, multipart и сжатые представления
в streaming режиме остаются непрозрачными:
прокси не разбирает части multipart и не распаковывает `Content-Encoding`.
HTTP framing, в том числе границы chunked-порций, на каждом соединении формируется
заново; сохранение исходных границ не обещается. Глобальный MVC `FormContentFilter`
отключён в этом примере, чтобы не читать form PUT/PATCH/DELETE до прокси.

Для upload-проверок нужен upstream, принимающий соответствующие методы и пути.
Замените `payload.bin` на существующий файл:

```bash
curl --http1.1 -H 'Expect:' --data-binary @payload.bin 'http://127.0.0.1:8081/proxy/upload'
curl --http1.1 -X PUT -H 'Expect:' -H 'Transfer-Encoding: chunked' --data-binary @payload.bin 'http://127.0.0.1:8081/proxy/upload'
curl --http1.1 -H 'Expect:' -F 'file=@payload.bin' -F 'description=sample' 'http://127.0.0.1:8081/proxy/upload'
```

Для проверки form PUT без предварительного разбора тела:

```bash
curl --http1.1 -X PUT -H 'Expect:' -H 'Content-Type: application/x-www-form-urlencoded' --data-binary 'x=%2F&x=2' 'http://127.0.0.1:8081/proxy/form'
```

## Настройки клиента

Настройки находятся в `src/main/resources/application.yml`, префикс —
`example.proxy`. Адрес `upstream` допускает base path; user info, query и fragment
в нём запрещены. `PROXY_UPSTREAM` переопределяет адрес по умолчанию.

| Параметр | По умолчанию | Значение |
| --- | --- | --- |
| `chunk-bytes` | `65536` | Порция upload, буфер чтения upstream и массив переносимой записи; допустимо 1024–65536 байт |
| `max-response-headers-bytes` | `16384` | Положительный предел HTTP metadata upstream: headers, trailers и chunk extensions; превышение даёт `502` до commit или обрыв уже начатого ответа |
| `max-concurrent` | `128` | Общий предел принятых незавершённых обменов; при заполнении `503` |
| `max-connections-per-destination` | `64` | Предел соединений клиента на destination |
| `max-queued-requests-per-destination` | `64` | Предел очереди ожидания соединения на destination |
| `selectors` | `2` | Сетевые selectors отдельного клиента |
| `connect-timeout` | `3s` | Таймаут установки соединения клиента |
| `exchange-timeout` | `60s` | Общий срок обмена, включая очередь и передачу тела |
| `idle-timeout` | `30s` | Таймаут отсутствия сетевого трафика, в том числе при активном обмене |

Лимиты destination общие: они не умножаются на `selectors`. Это семантика Jetty
HttpClient, которая отличается от пулов по event loop в основном Netty-прокси.
Размер порции не является пределом всей памяти: клиент, контейнер, TLS и сокеты
используют собственные буферы.

Общий `exchange-timeout` дополнительно должен помещаться в наносекундный `long`;
переполнение отклоняется при запуске, до приёма запросов.

Один клиент создаётся на приложение и останавливается вместе с ним. Конфигурация
явно меняет стандартное поведение `HttpClient`: redirect выключен, cookie store
пустой, прозрачные декодеры и обработчики автоматической аутентификации/upgrade
отключены. Клиент не добавляет свои `User-Agent` и `Content-Type` при отсутствии
этих заголовков во входящем запросе. Обработчики промежуточных `100/102/103` и fallback для остальных `1xx`, кроме
`101`, сохраняют корректное ожидание финального ответа. Cookies из заголовков текущего запроса и ответа
передаются; клиент не хранит cookies между пользователями. HTTPS использует
обычную проверку доверия сертификату и имени узла; trust-all не включён.

Мелкие уже доступные фрагменты ответа объединяются в порцию не больше
`chunk-bytes`, максимум из 1024 фрагментов. Прокси не ждёт новые данные ради
заполнения порции: одиночные события и заголовки отправляются сразу. Крупные
фрагменты не копируются ради объединения; при объединении допускается одна
порция и один отложенный крупный chunk. Это ограничение полезных данных, а не
всей памяти JVM, parser или сокетов.

## Добавление заголовков

Объявите Spring bean типа `ProxyHeaderCustomizer` в конфигурации приложения.
Глобальные hooks вызываются по `@Order`, затем customizers конкретного
`ProxyRequestPlan` в порядке добавления. Без hooks заголовки дополнительно
не меняются. `@Order` задаёт порядок от меньшего значения к большему, одинаковый
для обоих направлений.
Например, в пакете `dev.example.jettyclient`:

```java
package dev.example.jettyclient;

import dev.example.jettyclient.proxy.ProxyHeaderCustomizer;
import dev.example.jettyclient.proxy.ProxyHeaderEditor;
import dev.example.jettyclient.proxy.ProxyRequestMetadata;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
class ExampleHeaders {
    @Bean
    @Order(10)
    ProxyHeaderCustomizer serviceHeader() {
        return new ProxyHeaderCustomizer() {
            @Override
            public void customizeRequest(ProxyRequestMetadata request, ProxyHeaderEditor headers) {
                headers.set("X-Example-Service", "gateway");
            }

            @Override
            public void customizeResponse(ProxyRequestMetadata request, int status, ProxyHeaderEditor headers) {
                headers.add("X-Example-Service", "gateway");
            }
        };
    }

    @Bean
    @Order(20)
    ProxyHeaderCustomizer laterHeader() {
        return new ProxyHeaderCustomizer() {
            @Override
            public void customizeRequest(ProxyRequestMetadata request, ProxyHeaderEditor headers) {
                if (headers.values("X-Example-Service").contains("gateway")) {
                    headers.add("X-Example-Stage", "after-service");
                }
            }

            @Override
            public void customizeResponse(ProxyRequestMetadata request, int status, ProxyHeaderEditor headers) {
                headers.set("X-Example-Upstream-Status", Integer.toString(status));
            }
        };
    }
}
```

`set` заменяет все значения заголовка, `add` сохраняет предыдущие, `remove`
удаляет их без учёта регистра имени. `values` возвращает неизменяемый снимок
текущих значений. Повторные `Set-Cookie` не объединяются в одну строку; cookies
upstream добавляются к уже выставленным предыдущими Filters cookies приложения.

`ProxyRequestMetadata` содержит method, готовый upstream URI и глубокий
неизменяемый снимок исходных заголовков. Ключи `headers()` — в нижнем регистре;
`headerValues(name)` ищет без учёта регистра. В response hook передаётся тот же
снимок запроса, а не изменённые первым hook заголовки. Servlet, тело и объект
сетевого запроса в API не предоставляются.

Каждый hook вызывается один раз: request — до отправки upstream, response —
для финального ответа до установки и отправки заголовков клиенту. Beans общие
для параллельных обменов и должны быть потокобезопасными. Hooks обязаны быстро
завершаться без блокировок, чтения тела, обращения к сети или БД: на том же
владельце обмена обрабатываются отмена и deadline. Редактор нельзя сохранять или
передавать другому потоку; после завершения обработки его изменение запрещено.

После hooks и повторной очистки проверяется итоговый `Authorization`:
`NTLM` и `Negotiate` отклоняются с `501` до upstream I/O. Эти схемы могут привязать
идентичность к TCP-соединению, а пул общий для разных клиентов. Request-scoped
`Basic`/`Bearer` продолжают передаваться. Поля из исходного `Connection`
остаются удалёнными даже после добавления через hook.

Редактор запрещает менять `Host`, `Content-Length`, `Expect`, hop-by-hop
заголовки и описание непрозрачного тела: `Content-Type`, `Content-Encoding`,
`Content-Range`. Прокси повторно фильтрует итоговые заголовки, включая имена,
перечисленные исходным `Connection`; такие поля не возвращаются даже через
customizer. Неверное имя, CR/LF или другие недопустимые символы в значении, попытка изменить
защищённый заголовок или исключение hook дают локальный `500` до commit.
При ошибке request hook запрос upstream не отправляется. Статус, URI, тело и
жизненный цикл обмена это расширение не меняет.
`AssertionError` и `LinkageError` прикладного hook также дают локальный `500`.

## Request plan, завершение и преобразование ответа

`ProxyRequestPlan.builder(target)` задаёт полный raw URI, включая query. Target
проходит проверку engine: HTTP(S), host, допустимый port, без user info/fragment.
`.timeout(Duration)` переопределяет общий deadline для одного обмена; значение
должно быть не меньше 1 мс и помещаться в наносекундный `long`.
`.customizer(...)` добавляет расширение только этого запроса. В planner можно
снять backend/user/request-id context и захватить immutable значения в hook;
к Servlet, session и thread-local context из hook обращаться не следует.

`ProxyExchangeExecutor.forward(request, response, suppliedAsync, plan)` возвращает
`ProxyCall`. При `suppliedAsync == null` engine сам вызывает
`startAsync(request, response)`. Для текущего `HttpProxy` можно передать уже
начатый async, но он должен быть создан через `startAsync(request, response)`
для той же пары wrappers. Чужой supplied context отклоняется без его завершения.
После передачи запроса engine нельзя продолжать Filter chain или повторять его
через другой транспорт.

`ProxyCall.completion()` сообщает status, failure, uploadedBytes, downloadedBytes,
elapsed и `success()`. Это результат передачи, а не классификация HTTP-статуса:
успешно переданный upstream `404` не является transport failure. UploadedBytes —
байты, прочитанные из Servlet, без гарантии доставки backend; downloadedBytes учитывает успешно завершённые downstream writes.
После commit status сохраняет отправленный HTTP-код, даже при failure. Completion
происходит после cleanup; callback не должен обращаться к Servlet, который
контейнер уже мог переработать. `cancel(cause)` отменяет только этот обмен.
Дорогие обработчики результата выполняйте через `whenCompleteAsync` на своём
ограниченном executor, передавая только подготовленные данные.

`.responseBody(policy)` включает opt-in buffered rewrite для выбранных полных
ответов с телом без compression/range semantics и `Cache-Control: no-transform`
в запросе или ответе. Policy может выбирать в том числе `201` и `404`.
Возврат `null` оставляет streaming. Перед преобразованием engine накапливает bounded direct
segments с `maxInputBytes` и не более 65 536 компонентов; output ограничен
`maxOutputBytes`; число output segments также ограничено 65 536 до создания views.
Rewriter выполняется на 2 worker threads с очередью `max-concurrent` задач
(по умолчанию 128) и возвращает `ProxyResponseBody` из read-only `ByteBuffer` segments. Неизменённые
участки разрешено заимствовать из input; engine удерживает их до окончания
worker и записей. Новые вставки не должны требовать собственного pooled cleanup.
Без policy обычный streaming путь не собирает тело целиком и не занимает worker.

Это точка переноса алгоритмов `ByteSplice`/`ByteRewriteChain`; текущие Netty
переписчики и бизнес-правила HTML/CSS автоматически не перенесены. Bounded
prefix-пример, точный mapping legacy API и правила lifecycle приведены
в [MIGRATION.md](MIGRATION.md).

До commit engine возвращает `502` при ошибке upstream, `504` по deadline и `503`
при заполнении admission/worker queue, остановке либо cancellation. Ошибка
прикладного hook даёт `500`. Legacy fallback `500`/`408` с текстом ошибки не
переносится автоматически. После commit повреждённый ответ обрывается.

## Границы примера

`CONNECT` и `Upgrade` не поддерживаются (`501`); любой входящий `Expect`
отклоняется (`417`), поэтому upload-команды выше явно убирают этот заголовок.
Туннели, WebSocket и HTTP/2 в пример не входят. Не заявляется передача trailers
или промежуточных ответов downstream. Redirect upstream возвращается клиенту,
а не выполняется самим прокси.

Регистр method token сохраняется: `HEAD` и extension method `head` имеют разную
семантику. Для точных `OPTIONS`/`TRACE` учитывается `Max-Forwards`: положительное
значение уменьшается (с ограничением `Integer.MAX_VALUE`), невалидное даёт `400`.
При нуле запрос не уходит upstream: OPTIONS возвращает локальный пустой `200`,
TRACE — `501`. Локальные ответы не проходят response hook, предназначенный для upstream.

EOF клиентского тела и успешное завершение upstream sender учитываются отдельно.
Полностью полученный ответ upstream сохраняется даже при поздней ошибке последней
записи запроса. Записи и буферы освобождаются по callbacks, включая остановку
executor с уже принятой, но ещё не выполненной drain-задачей.

Если клиент сбросил GET-соединение до любых response headers и нового I/O ещё нет,
Jetty может обнаружить это только при следующем I/O или по общему deadline.
Admission и upstream в таком случае удерживаются до этого момента; мгновенное
обнаружение TCP reset не обещается. Отдельная проверка подтверждает cleanup и recovery
в пределах настроенного срока обмена с учётом завершения callbacks.

Неблокирующий I/O и использование `ByteBuffer` сами по себе не означают полного
отсутствия копий. Это пример streaming-интеграции, без обещания zero-copy.
Счётчик `activeRequests()` отражает незавершённые обмены примера; он не является
контрактом ожидания физического закрытия TCP/TLS-канала.

Аутентификации и авторизации сейчас нет. При будущем добавлении Spring Security
проверки доступа должны выполняться всей применимой цепочкой **до** proxy Filter.
Потребуется отдельно проверить wrappers, async lifecycle и фильтры, читающие тело;
в этом модуле такие механизмы не настраиваются.
