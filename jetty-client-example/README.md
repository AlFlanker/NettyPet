# Standalone demo: Servlet Filter + Jetty HttpClient

Это запускаемый пример поверх двух обычных библиотек:

| Модуль | Назначение |
| --- | --- |
| [jetty-proxy](../jetty-proxy/README.md) | Core без Spring: streaming, bounded fetch, TLS, header/body/error policies |
| [jetty-proxy-spring](../jetty-proxy-spring/README.md) | Явный `@Import`, binding/lifecycle и generic Filter; без маршрутов и server settings |
| `jetty-client-example` | Только standalone main, локальный MVC и demo registration `/proxy` |
| [jetty-proxy-integration-example](../jetty-proxy-integration-example/README.md) | Отдельный host с собственным HttpProxyFilter, двумя handlers и resource loader |

Для подключения к приложению выбирайте library JAR, а не этот Boot executable.
Пакет public API сохранён: `dev.example.jettyclient.proxy`. Наличие Spring adapter
на classpath ничего не регистрирует: включение через `@Import` и маршруты остаются
у host. Spring Security и feature toggle не добавлены.

[MIGRATION.md](MIGRATION.md) сопоставляет новые APIs с example `examples/proxy/`.
Текущая реализация и проверки: [IMPLEMENTATION.md](../jetty-proxy/IMPLEMENTATION.md).
Реальный портал не предоставлен; встроенная host-проверка доказывает подключение
библиотек, а не полный перенос закрытых application classes.

## Запуск demo

Нужны JDK 21, Gradle 8.14.3 и отдельный upstream. В checkout есть `gradlew`,
но отсутствуют файлы `gradle/wrapper`; в этой среде используйте
`/workspace/.setup/gradle`, локально — установленный Gradle.

```bash
NETTYPET_GRADLE=/workspace/.setup/gradle
"$NETTYPET_GRADLE" :jetty-client-example:build
PROXY_UPSTREAM=http://127.0.0.1:9000 \
  java -jar jetty-client-example/build/libs/jetty-client-example-1.0.0.jar
```

Альтернатива:

```bash
"$NETTYPET_GRADLE" :jetty-client-example:bootRun \
  --args='--server.port=8082 --jetty.proxy.max-concurrent=32'
```

Demo слушает `127.0.0.1:8081`, default upstream — `127.0.0.1:9000`.
Эти настройки находятся только в demo, не в library JAR.

```bash
curl -i http://127.0.0.1:8081/local
curl -i http://127.0.0.1:8081/proxy
curl --path-as-is -i 'http://127.0.0.1:8081/proxy/a%20b?x=%2F&x=2'
```

`/local` возвращает `local`. `JettyProxyFilter` зарегистрирован demo на `/*`;
default planner выбирает `/proxy` и `/proxy/*`, для остального возвращает `null`.
При upstream `http://127.0.0.1:9000/base`:

| Входящий запрос | Upstream |
| --- | --- |
| `/proxy`, `/proxy/` | `/base/` |
| `/proxy/a?x=1&x=2` | `/base/a?x=1&x=2` |
| `/proxying` | Продолжает локальную chain |

Raw path/query сохраняются без повторного кодирования. Неоднозначный URI может
отклонить контейнер до Filter. Дополнительный Servlet adapter оставлен для примера,
но его registration выключена; общий engine принадлежит Spring configuration.

## Тела и настройки

По умолчанию запросы и ответы передаются streaming, без сборки целого тела.
Multipart не разбирается и не пересобирается; compression остаётся непрозрачной.
HTTP framing создаётся заново на каждом соединении.

```bash
curl --http1.1 -X POST -H 'Expect:' -F 'file=@payload.bin' \
  http://127.0.0.1:8081/proxy/upload
curl --http1.1 -X PUT -H 'Expect:' -F 'file=@payload.bin' \
  http://127.0.0.1:8081/proxy/upload
curl --http1.1 -X PUT -H 'Expect:' -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-binary 'x=%2F&x=2' http://127.0.0.1:8081/proxy/form
```

Для demo глобально отключён MVC `FormContentFilter`. Это затрагивает также
локальные form PUT/PATCH/DELETE. Library этого не делает; host-пример ставит
application proxy Filter до generic form parsing и сохраняет локальный MVC.

Префикс transport binding — **`jetty.proxy`**:

| Параметр | Default | Значение |
| --- | --- | --- |
| `upstream` | `http://127.0.0.1:9000` | Только назначение default demo planner |
| `chunk-bytes` | `65536` | Upload/response порция, portable scratch; 1024…65536 |
| `max-response-headers-bytes` | `16384` | Предел upstream HTTP metadata, не тела |
| `max-concurrent` | `128` | Admission streaming engine |
| `max-connections-per-destination` | `64` | Соединения на destination |
| `max-queued-requests-per-destination` | `64` | Ожидание соединения |
| `selectors` | `2` | Selectors отдельного клиента |
| `connect-timeout` | `3s` | Установка соединения; также watchdog локального error write |
| `exchange-timeout` | `60s` | Основной deadline обмена, включая header stages и rewrite |
| `idle-timeout` | `30s` | Отсутствие трафика в соединении |
| `destination-idle-timeout` | `60s` | Удаление пустых простаивающих destinations |
| `fetch.max-concurrent` / `fetch.decoder-threads` / `fetch.max-queued` | `8 / 2 / 8` | Отдельный бюджет полного fetch, не streaming semaphore |

URI, положительные durations и nanos overflow проверяются до обслуживания запросов.
В Spring adapter fetch concurrency/queue дополнительно ограничены `max-concurrent`.
Socket idle timeout не удаляет destination metadata; для этого нужен destination TTL.
Selectors не равны числу всех потоков JVM, chunk size не задаёт точный предел RSS.

## Прикладные политики

`ProxyRequestPlan` задаёт explicit target, deadline и request-local policies.
Глобальные `ProxyHeaderCustomizer` beans исполняются по `@Order`, затем hooks
плана. Редактор защищает Host/framing/body metadata; итог очищается от hop-by-hop
и исходных Connection nominations. Hooks быстрые, не читают Servlet/БД/сеть.

Для внешнего cookie storage используйте `requestHeadersAsync`/
`responseHeadersAsync`: stage возвращает immutable `ProxyHeaderPatch`, выполняя
I/O на executor приложения. Передача editor или Servlet в worker недопустима.
Готовые generic URL policies — `HeaderRewriteCustomizer` и `LinkRewritePolicy`;
bounded `ProxyErrorPolicy` возвращает локальный текст до commit. Полный контракт,
лимиты и примеры — в [core README](../jetty-proxy/README.md).

Cookies/Vary приложения сохраняются при применении upstream headers; при локальной
ошибке восстанавливаются application headers, metadata тела очищается и ставится
`no-store`. После commit повреждённое тело заканчивается abort, а не ложным EOF.
`ProxyCall` поддерживает отмену и completion без чтения уже переработанного Servlet.
Для отдельного GET/строкового include используйте bounded fetch через application
loader, сохраняя отдельные wire/decoded/text budgets.

## Проверки и документация

[PDF-гайд](docs/jetty-client-guide.pdf), [исходник](docs/guide.md) и
[сборка PDF](docs/README.md) описывают библиотечные границы, streaming, bounded
операции, lifecycle и приложение host. Для проверки всего набора:

```bash
"$NETTYPET_GRADLE" build
```

История до выделения библиотек: [INTEGRATION_REVIEW.md](INTEGRATION_REVIEW.md)
и [COUNCIL_REVIEW.md](COUNCIL_REVIEW.md); их 150/395 и 188/433 относятся к прежним
runtime, не к текущей структуре модулей. Короткий замер
[filter-integration-performance.json](filter-integration-performance.json)
159,96 → 176,83 rps для GET 8 МиБ также исторический (`e078071`). Он не измеряет
новые fetch/policies и не доказывает устойчивое ускорение или production capacity.

Security, feature toggle, response cache, SSO/storage, backend registry и обновление
настроек принадлежат приложению. CONNECT, upgrade, Expect, retries и HTTP/2 не входят
в контракт. До новых I/O reset GET перед первыми headers может обнаружиться только
по deadline. Неблокирующий ByteBuffer I/O не означает полного отсутствия копий.
