# Ролевое ревью и исправления

Дата проверки: 20 октября 2026 года.

Этот отчёт описывает предыдущий аудит двухмодульной версии (82 теста).
Следующее расширение до трёх модулей описано в [RESILIENCE.md](RESILIENCE.md);
новый аудит шести ролей — в [RESILIENCE_REVIEW.md](RESILIENCE_REVIEW.md);
актуальные результаты сборки находятся в `verification.json`.
Объект: два Gradle-модуля `app` и `proxy`, Spring Boot 4.1.1, Jetty 12.1.12,
Spring WebMVC 7.0.9, Netty 4.2.19.Final, Java 21.

## Коллектив и компетенции

Для независимых проходов использованы пять ролей. Это модель технической команды;
роли проверяли один и тот же код с разных сторон и сверяли выводы с тестами.

| Роль | Специализация | Что проверялось |
| --- | --- | --- |
| Эксперт HTTP и безопасности протокола | Framing, request/response smuggling, URI, pooled authentication | CL/TE, статусы, hop-by-hop поля, Host, allowlist, повторное использование соединений |
| Инженер Netty и конкурентности | Event loops, reference counting, bounded queues | Владение каналом, ByteBuf, pending acquire, backpressure, shutdown, поздние callbacks |
| Специалист Servlet/Jetty | Async lifecycle, nonblocking streams, контейнерные locks | startAsync, onError/onComplete, recycle request, committed responses, HEAD и ранние ответы |
| SRE | Перегрузка, deadlines, DNS/TLS, наблюдаемость | Admission, лимиты пулов, классификация отказов, освобождение ресурсов, метрики |
| Архитектор API и расширений | Контракты SPI, изоляция транспорта, стоимость метаданных | Processor chain, immutable snapshot, границы модулей, исключения и прикладная кастомизация |

## Существенные находки

P1 означает риск нарушения корректности, зависания либо изоляции запросов.
P2 означает ошибочное поведение на конкретном граничном сценарии.
Приоритет не является оценкой CVSS.

| Приоритет | Проблема и последствие | Исправление | Подтверждение |
| --- | --- | --- | --- |
| P1 | `ReadListener/WriteListener.onError` считался завершением AsyncContext. При timeout=0 мог оставаться активный запрос, задерживающий graceful shutdown Jetty | Отдельный `servletIoFailed`: немедленный запрет Servlet I/O, неблокирующий abort, cleanup на owner loop | Реальный RST во время неполного upload первоначально оставлял Jetty в shutdown до timeout теста; после исправления проходит. Дополнительно проверены callback и синхронные read/write IOException |
| P1 | Проверка флага остановки без согласования с I/O оставляла гонку с завершением/recycle Servlet-объектов | Короткий `servletGuard` на обмен; остановка синхронна с callback; повторные проверки после callback-capable вызовов | Тест уже поставленного в очередь pump после отмены, позднего сигнала, сохранности scratch; проверены реальные пути locks/callbacks Jetty 12.1.12 |
| P1 | Исключение abort adapter или `Error` во время подготовки могло обойти освобождение admission/буферов | Cleanup в `finally`, явная передача владения permit, идемпотентное освобождение | `ProxyEngineOwnershipTest`, `BackpressureTest`: исключения Processor, конструктора, abort и повторное завершение |
| P1 | Внешний Processor мог проглотить отказ после `proceed()`; транспорт мог продолжить отправку | Сохранение отказа на уровне всего выполнения цепочки | Тесты вложенных отказов, отказа после proceed и повторного вызова продолжения |
| P1 | Изменяемый план передавался в async transport: позднее изменение URI/headers могло нарушить согласованность проверки origin, пула и Host | Неизменяемый `PreparedRequest` после Processor; транспорт использует только этот снимок | `PreparedRequestTest`: изменение исходного URI, headers и списков не меняет снимок |
| Условный P1 | NTLM/Negotiate могут хранить identity на TCP-соединении, а общий pool не привязан к клиенту | Итоговые Authorization NTLM/Negotiate отклоняются с 501 до I/O; Basic/Bearer сохраняются | Тесты всех значений заголовка, регистра, схем после Processor; connection binding намеренно не реализован |
| P2 | Предыдущий обычный отказ маскировал последующий `Error` в Processor | Первый Error имеет приоритет над Exception, исходный объект ошибки пробрасывается | Две регрессии с `assertSame` для Error после ProxyRejection и нескольких Error |
| P2 | TCP/DNS/TLS ошибки, pool overload и Servlet disconnect смешивались | Upstream failure → 502; timeout → 504; pool/admission pressure → 503; Servlet I/O failure → abort/cancelled | Реальный connection refused; wrapped DNS timeout; pool deadline; read/write IOException и метрики |
| P2 | Полный exchange timeout запускался заново после ожидания первого задания event loop | Из исходного бюджета вычитается уже прошедшее время; просроченный запрос завершается до acquire | Детерминированный тест 504 без обращения к pool |
| P2 | Netty принимал upstream-статусы 000 и 600: первый ожидал deadline, второй передавался клиенту | Проверка диапазона 100..599 перед обработкой 1xx | Два теста с raw upstream; сценарий 000 удерживает соединение открытым |
| P2 | HTTP/1 запрос без тела получал искусственный chunked framing; неподдерживаемый transfer coding мог терять смысл после удаления TE | Для HTTP/1 без CL/TE длина тела равна нулю; разрешён только отсутствующий TE или один chunked; прочие кодировки явно отклоняются | Bodyless GET, HTTP/2 unknown-length metadata, HTTP/1 chunked, raw gzip transfer coding |
| P2 | Ответ 205 с ненулевым CL нарушал безтельную семантику | Некорректный ответ отвергается; корректный 205/CL0 проходит | Две сетевые регрессии |

## Дополнительные укрепления

- Строгие строки и HTTP headers, запрет повторного CL и неоднозначного TE/CL для
  ответов с телом. У HEAD и безтельных статусов есть отдельные правила Netty decoder.
- Ограниченный асинхронный DNS: TTL, query timeout, число queries. TLS 1.2/1.3 через
  JDK provider с проверкой hostname и стандартными доверенными CA.
- ChannelGroup учитывает свободные, выданные и подключающиеся upstream-каналы.
  Idle timers отменяются. Lifecycle shutdown не разрешён из собственного I/O loop;
  interrupt lifecycle-потока не обрывает cleanup на полпути.
- Пока abandoned pool acquire не завершился, admission остаётся занятым. Это
  ограничивает не только живые запросы, но и покинутые waiters.
- Метрики обновляются при допуске/завершении, а не общими атомарными счётчиками на
  каждом chunk. Набор счётчиков фиксирован, без меток из пользовательских URI.
- В комментариях описаны владение ресурсами, инварианты backpressure, смысл
  lifecycle guard и ограничения адаптера контейнера.

## Архитектура после ревью

`app` владеет маршрутом `/proxy`, Spring-конфигурацией, Jetty adapter и Processor beans.
`proxy` предоставляет `ProxyEngine` и SPI, использует Servlet API, Netty и SLF4J;
в его исходниках нет зависимостей на Spring или Jetty и нет бизнес-правил маршрутизации.
`/proxying` остаётся локальным маршрутом, context path не попадает в upstream URI.

Тела не агрегируются. Upload держит один буфер in flight; download использует
`AUTO_READ=false`, ограниченную очередь и Servlet readiness. Scratch нельзя менять
до готовности предыдущей записи. Указанный byte budget ограничивает payload очереди,
а не суммарную память JVM, TLS, allocator и ОС.

## Результат проверки

Выполнен Gradle 8.14.3 `clean build` на JDK 21: **BUILD SUCCESSFUL**.
**82 теста: 28 в app, 54 в proxy; 0 failures, 0 errors, 0 skipped.**
Netty leak detector включён в режиме paranoid. Его отсутствие сообщений не заменяет
длительную проверку утечек под нагрузкой.

| Набор | Количество |
| --- | ---: |
| API: Headers, ProcessorPipeline, ProxyMetrics | 19 |
| Core: HeaderRules, UpstreamUri | 10 |
| Transport: backpressure, lifecycle, ownership, snapshot, failures | 25 |
| App: реальные Jetty/upstream сценарии и unit-тесты фильтра | 28 |

В исполняемом app JAR проверены фактические версии Boot, WebMVC, Jetty и всех Netty
модулей. Используется ровно Netty 4.2.19.Final. Сборка и тесты приложены как исходники;
переносимая команда повторения: `./gradlew clean build`.

## Границы заключения

Найденные существенные дефекты исправлены и покрыты регрессиями. Прохождение ревью
и тестов не измеряет пропускную способность или p99: нагрузочные и soak-тесты не
проводились. Перед внедрением нужно проверить профиль реального трафика, медленных
клиентов/upstream, длительные HTTPS-потоки, heap/direct memory, RSS и повторный deploy.

Сетевые тесты используют HTTP/1.1; HTTP/2 downstream и TLS под нагрузкой не проверены.
Upstream HTTP/2, gRPC, CONNECT, WebSocket/upgrades, trailers и connection-bound auth
не поддерживаются. Промежуточные 1xx не пересылаются. SSE ограничен абсолютным deadline.

Processor — доверенный синхронный код приложения до async, вне exchange timeout.
Он не должен блокироваться или сохранять continuation. Уже отправленный запрос
нельзя отменить поздним запрещённым вызовом chain. Аутентификация приложения должна
сработать до перехватывающего ProxyFilter; пример её не включает.

Allowlist задаёт доверенные origins, а не IP-фильтр после DNS. Pool рассчитан на
корректный upstream; полная изоляция от злонамеренно задержанных дополнительных
ответов на повторно используемом HTTP/1.1 соединении не заявляется. Нет retries
произвольного потокового запроса. Подробнее — README.

Основание для ограничения NTLM: [официальная документация upstream connection binding](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#ntlm).
