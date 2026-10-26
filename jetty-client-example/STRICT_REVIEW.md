# Повторное строгое ревью Jetty HttpClient примера

24 октября 2026. Исходная версия — `d78598a` в master; runtime модуля до этого
ревью соответствовал `5d672f6`. Проверялся именно `jetty-client-example`,
без подключения Spring Security и без переноса команд основного Netty-прокси.
Исправленный runtime зафиксирован в `6d9c7f0`.

Пять специалистов независимо проверяли HTTP/маршрутизацию; pinned Jetty I/O
и владение памятью; concurrency/lifecycle; производительность и границы ресурсов;
API, конфигурацию и расширения. Исправления дополнительно проверялись другими
участниками. Прежнее положительное заключение не использовалось как доказательство.

## Найденные и исправленные дефекты

| Приоритет | Дефект и воспроизведение | Исправление / регрессия |
| --- | --- | --- |
| P1 | `NTLM`/`Negotiate` передавались в общий keep-alive pool. Fixture с привязкой principal к upstream socket показал перенос identity на следующий независимый downstream-запрос без Authorization. Это модель connection-bound сервера, не полноценный Windows NTLM handshake. | Проверка итогового Authorization после hooks и sanitization; `501` до upstream I/O. Protocol tests и injection через header hooks; Basic/Bearer остаются request-scoped. |
| P1 | У клиента был `maxResponseHeadersSize=-1`: parser принимал неограниченные metadata до hooks. Bounded baseline repro принял заголовок 32 КиБ, несмотря на входящий server limit 16KB. | `max-response-headers-bytes=16384`, обязательное положительное значение. Реальные oversized headers дают `502`; trailers/extensions после commit обрывают ответ. Тесты также проверяют recovery. |
| P1 | Portable writer мог получить синхронный `onError()` из `isReady/write/flush`, затем продолжить I/O и подтвердить success, пока событие ошибки стояло в очереди. | Первая ошибка фиксируется синхронно, pump проверяет её вокруг I/O; cleanup остаётся на владельце. Три регрессии падали до исправления, контроль сохранности pending массива проходил. |
| P1 | Jetty QTP при остановке отбрасывает уже принятые queued jobs. Обычная drain lambda не имела cleanup-контракта: WIP оставался занят, Exchange и admission не освобождались. | Per-generation `Runnable + Closeable` drain task с единым CAS: выполнение либо перенос в fallback executor. Тест использует настоящий QTP с занятой worker и остановкой очереди. |
| P1 | Producer EOF ошибочно считался завершением sender. Полный `413` мог быть прерван из-за поздней ошибки terminating write после `content.close()`. | Отдельный `requestSucceeded`; sender останавливается после response success. Успешный response без response failure сохраняется при request failure. Детерминированный тест использует настоящий AsyncRequestContent и допустимый Jetty callback ordering; это контрактный, не socket-timing repro. |
| P2 | Jetty `HttpRequest.method(String)` переводит token в верхний регистр: `foo` уходил как `FOO`; `head` ошибочно получал HEAD-семантику. | `ProxyHttpClient` сохраняет исходный token, включая copy; bodyless-проверка сравнивает точный `HEAD`. Wire tests проверяют extension methods и тело `head`. |
| P2 | OPTIONS/TRACE игнорировали `Max-Forwards`: ноль достигал upstream, положительное значение не уменьшалось. | После hooks применяется RFC 9110 §7.6.2: decrement с безопасным cap, malformed → `400`, ноль → локальный OPTIONS `200` / TRACE `501`. Это относится к точным методам, не одноимённым extension tokens другого регистра. |
| P2 | HEAD/304 с допустимым `Transfer-Encoding: gzip, chunked` metadata без тела ошибочно давали `502`. | Coding проверяется для реального тела; metadata exact HEAD/304 не требует декодирования. Независимые raw-response fixtures и контроль Content-Length HEAD. |
| P2 | `205` с положительным Content-Length мог успеть отправить downstream `205`, прежде чем body callback обнаруживал запрещённое содержимое. | Положительная длина отклоняется с `502` до hooks/commit. Raw fixture `205 + CL3 + abc` проверяет пустой `502` и cleanup. |
| P2 | Exchange duration помещалась в миллисекунды, но переполняла `toNanos()`: конфигурация принималась, запросы получали `502`. | Валидация `exchange-timeout` при binding; тесты переполнения, точной допустимой границы и минимума. Ограничения connect/idle не ужесточались произвольно. |
| P3 | AssertionError/LinkageError прикладного hook классифицировались как upstream `502`. | Локальный `500` с cleanup, четыре HTTP-регрессии request/response. Обработка специально не расширена на VM errors и ThreadDeath. |

Ключевые файлы исправлений: `ProxyHttpClient`, `ProxyHeaders`, `JettyProxyServlet`,
`Downstream`, `JettyProxyConfiguration`, `JettyProxyProperties` в
`src/main/java/dev/example/jettyclient/proxy/`.

## Проверки и доказательства

Первоначальные 40 тестов проходили до ревью. Дополнительные проверки выявили
ошибки, которые они не покрывали:

- Portable reentrancy до исправления: 3 failures из 4.
- Protocol + queued-shutdown до исправления: 24 failures из 28
  (27 protocol cases и один QTP; позднее добавлен отдельный 205 case).
- Producer EOF / early response до исправления: один failure;
  23 header-customization cases в том же прогоне прошли.

Итоговый модуль содержит **96 тестовых случаев**:

| Класс | Случаев | Что подтверждает |
| --- | ---: | --- |
| `JettyProxyIntegrationTest` | 21 | Большие тела, multipart POST/PUT, form/chunked/gzip, streaming, отмена и shutdown |
| `JettyProxyProtocolTest` | 28 | Auth policy, method case, Max-Forwards, HEAD/304 metadata и неверный 205 |
| `JettyProxyHeaderCustomizationTest` | 23 | Порядок beans, immutable metadata, protected fields, sanitization, injection и ошибки hooks |
| `JettyProxyResponseLimitsTest` | 3 | Ограничения headers/trailers/extensions, обрыв committed тела, recovery |
| `ResponseContentBufferTest` | 6 | Batching, границы, carry/EOF и владение chunks |
| `DownstreamPortableReentrancyTest` | 4 | Синхронная ошибка wrappers и сохранность массива при cancel |
| `JettyProxyPropertiesTest` | 9 | Duration overflow и границы конфигурации |
| `JettyProxyQueuedDrainShutdownTest` | 1 | Потеря принятой задачи реальным QTP |
| `JettyProxyEarlyResponseAfterUploadEofTest` | 1 | Response success при late sender failure после producer EOF |

В тестах metadata limits transport deadline 30s и client timeout 25s длиннее
JUnit timeout 20s: обрыв не может засчитываться как успех за счёт этих таймеров.
Для socket fixtures используются отдельные loopback ports; негативные случаи
проверяют последующий корректный запрос, а не только выбранный HTTP-статус.

Полная `gradle build`: **341 тест**, failures/errors/skips — **0**:
`app` 74, `proxy` 87, `command-support` 84, `jetty-client-example` 96.
Для неизменённых модулей Gradle использует актуальные результаты; изменённый
модуль перекомпилирован и его тесты выполнены заново.

Финальный bootJar дополнительно прошёл 16 HTTP multipart smoke-проверок
(4 положительные и 12 негативных) и 8 запросов JMeter 5.6.3
(4 POST + 4 PUT), ошибок ноль. Приёмник и assertions проверили независимый manifest:
SHA-256 всего wire body размером 8 389 280 байт и всех трёх частей совпали.
Измерения и функциональный smoke использовали один JAR с SHA-256
`e54700d8d7f3c95e34d8645b435d0f061162bffb542323f23fe5edf28578e5f5`.

## Границы результата

Проверка GET reset до первых headers выявила ограничение Jetty Servlet-пути:
если дальнейшего I/O нет, reset может быть обнаружен лишь при следующем I/O
или по exchange deadline. В baseline диагностике с deadline 4s upstream оставался
занят через 0,2s/1,2s, закрылся примерно через 3,97s; затем admission восстановился.
Один native `Request.addFailureListener` этого не изменил. Регрессия текущей ветки
проверяет гарантированное закрытие и recovery, допуская и более раннее обнаружение.
Мгновенная отмена в этом состоянии не заявляется.

Callback записи подтверждает возврат владения памятью, не TCP delivery. Успешный
upstream возвращается в pool; `activeRequests()==0` не означает закрытие всех sockets.
Отдельный клиент не разбирает multipart и не преобразует тело; zero-copy всего
пути, retries, Security, WebSocket и HTTP/2 по-прежнему не заявляются.

Историческое сравнение с Netty находится в `performance-results.json`.
Контроль текущих изменений сохраняется отдельно в `strict-review-performance.json`:
GET 8 МиБ до/после ревью — 196,44/205,80 rps, CPU 5,386/5,034 мс на запрос,
p95 150/143 мс, p99 192/175 мс, ошибок ноль. Один общий стенд 4 CPU,
20 клиентов, одинаковые JDK/JVM/64 КиБ, прогрев и A/B/B/A по 12 секунд.
Tiny chunks — 111,65/113,68 rps; p99 28/33 мс в коротких пятисекундных прогонах.
Во всех 14116 запросах, включая прогрев, assertions/размер прошли; SHA-256
до и после фаз совпал. Генератор заметно делит CPU с прокси и upstream.
Эти короткие прогоны не доказывают устойчивое ускорение, предел системы или SLO.
Длительный soak и реальные производственные профили этим ревью не подменяются.
