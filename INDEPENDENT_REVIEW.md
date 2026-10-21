# Независимое ревью после оптимизации транспорта

Дата: 20 октября 2026 года. Исходная точка: версия со 155 тестами и сравнением аллокаций.
Ревью выполнено шестью отдельными агентами по техническим направлениям; седьмая роль
проверила выводы, исправления и общий результат. Это не внешняя сертификация.

## Состав и решение по выводам

| Роль | Независимая проверка | Решение итогового рецензента |
| --- | --- | --- |
| HTTP и безопасность | Framing, hop-by-hop после Processor, raw URI, доверенные origins, неполные ответы, exact Netty decoder | Нового дефекта не подтверждено; принят пробел сетевых регрессий для неправильных ответов upstream |
| Netty и конкурентность | Pool acquire/release, async close, idle timers, shutdown, ownership | Подтверждён и исправлен P2: повторная выдача idle-канала после начала закрытия |
| Servlet/Jetty | Native bridge, locks/callbacks, wrappers, async completion, end marker | Нового дефекта не подтверждено; добавлен тест остановки всего приложения с активным I/O |
| JVM и память | Payload budget, пустые chunks, retain/release, coalescing, границы выводов benchmark | Нового дефекта не подтверждено; добавлены проверки точной границы очереди и пустых contents |
| SRE | Наблюдаемость, graceful shutdown, ресурсы, условия испытаний | Закрыт операционный пробел: появился read-only JMX bridge в app; требования к стенду вынесены в TRIALS.md |
| Resilience4j | TimeLimiter signal против cleanup, Bulkhead permit, CB/HALF_OPEN, scheduler/cancellation | Подтверждены существующие контракты; общий deadline не следует считать доказательством отказа upstream |
| Итоговый рецензент | Проверка доказательств, RED/GREEN регрессии, общий build, границы трёх модулей | Исправления приняты; допуск к изолированным испытаниям после зелёного build, production GO требует результатов стенда |

## P2: выдача закрывающегося idle-соединения

`NettyTransport` при истечении idle timeout вызывал `channel.close()`. Во время
асинхронного TLS `close_notify` физический канал может оставаться `isActive()==true`.
Он ещё находился в свободной очереди пула, и `ChannelHealthChecker.ACTIVE` разрешал
выдать его следующему запросу. Возможное следствие — ложный upstream failure/обрыв.

Исправление: перед началом закрытия выставляется постоянный атрибут `RETIRING`;
health checker допускает только активные непомеченные каналы. Acquire отменяет
idle timer, но никогда не снимает признак начавшегося закрытия. Тот же порядок
применяется к закрытию свободного канала из-за незапрошенных данных или ошибки.

Доказательство: новый тест на реальном NIO-соединении задерживает физический close
outbound handler-ом, моделируя интервал TLS close_notify. До исправления тест
`idleExpiryCannotLeaseAChannelWhoseCloseHasStartedButIsStillActive` получил **тот же**
канал и упал на assertNotSame. После исправления он получает другой канал. Это
детерминированная модель async close, а не заявление о принудительно воспроизведённой
конкретной задержке SSL на реальном TLS-проводе. Вторая регрессия подтверждает, что
acquire до idle expiry отменяет таймер и сохраняет здоровое соединение.

Контракт сверен с исходниками Netty 4.2.19.Final: `SslHandler.safeClose`,
`SimpleChannelPool`, `FixedChannelPool`, `ChannelHealthChecker`.

## Наблюдаемость для испытаний

В `app` добавлен `ProxyDiagnostics`: read-only JMX attributes с фиксированным набором
полей. Доступны реальные занятые proxy slots, свободные Bulkhead permits, счётчики
транспорта, CB state/window/rejections, TL success/error/timeout. Readiness/liveness
отражают локальный Spring lifecycle; OPEN circuit отдельно описывает backend-группу.
Отсутствуют URI/client labels, HTTP endpoint, удалённый JMX connector и backend probe.

Spring exporter использует уникальный ObjectName на контекст и удаляет MBean при close.
`spring.jmx.enabled=false` отключает сам bean и его подписки на события. Три интеграционных
теста проверяют реальные счётчики после сетевого запроса, read-only контракт и события,
два контекста без collision/чужого unregister, отключённый режим.

Это точка интеграции мониторинга. Она не заменяет экспорт в платформу, внешние p99/error
измерения или учёт RSS/direct memory. Счётчик duration включает cleanup и не является
гистограммой latency. Поля читаются независимо; это не глобальный атомарный snapshot.

## Закрытые пробелы тестов

- Raw upstream: TE+CL, одинаковые повторные CL и header 17 KiB отклоняются с 502 до commit.
- Неверный chunk и усечённый Content-Length после фактически прочитанного клиентом
  префикса завершаются ошибкой чтения, а не успешным end marker. Admission восстанавливается,
  следующий запрос проходит.
- Payload queue принимает ровно лимит и отвергает следующий байт без лишнего retain;
  cleanup освобождает накопленные буферы и admission.
- 4096 пустых contents не создают очередь/записи/flush; следующий реальный короткий
  фрагмент передаётся без ожидания.
- Одновременные незавершённые upload/download при `ApplicationContext.close()`:
  ограниченный grace, отсутствие ложного успешного завершения, физическое закрытие
  обоих upstream-сокетов, возврат обоих admission и остановка proxy I/O threads.

Общий прогон также выявил дефект старой проверки перегрузки: она приравнивала получение
клиентом HTTP-ответа к завершению внутреннего cleanup. Это разные события. Проверка
исправлена на ограниченное ожидание фактического возврата permit; код транспорта ради
ускоренного возврата admission не менялся. Остальные аналогичные проверки уже ожидают cleanup.

## Проверка зависимостей и границы безопасности

Runtime JAR проверяется на согласованные версии Jetty 12.1.12, Netty 4.2.19.Final,
Resilience4j 2.4.0 и отсутствие сервера Tomcat. `tomcat-embed-el` — реализация EL,
не Tomcat web server. Вложенные proxy/resilience JAR должны совпадать с отдельно собранными.

Ограниченная выборка официальных advisories не потребовала изменения этих версий:
[GHSA-xc35-c22g-239h](https://github.com/jetty/jetty.project/security/advisories/GHSA-xc35-c22g-239h)
и [GHSA-85fq-fc5f-7j7g](https://github.com/jetty/jetty.project/security/advisories/GHSA-85fq-fc5f-7j7g)
указывают исправление ветки 12.1 в 12.1.12;
[GHSA-pvjx-v7vp-62vq](https://github.com/netty/netty/security/advisories/GHSA-pvjx-v7vp-62vq)
— в Netty 4.2.18.Final, ниже используемой версии. Последний относится к HttpServerCodec,
тогда как upstream transport использует HttpClientCodec. Это не полный SCA всей
транзитивной цепочки; отсутствие других уязвимостей не заявляется.

Аутентификация должна предшествовать ProxyFilter или выполняться доверенным входным
контуром. Origin allowlist не является DNS/IP firewall. HTTP/2 downstream, HTTP/2 upstream,
CONNECT, WebSocket, gRPC и trailers не входят в подтверждённый режим данной поставки.

## Итог

Текущие точные результаты build и тестов находятся в `verification.json`.
`clean build --rerun-tasks` завершился успешно за 39 секунд: **168 тестов**
(app 65, proxy 85, resilience 18), 13 новых; failures/errors/skipped — 0.
Вложенные библиотеки совпадают с отдельно собранными, версии runtime согласованы,
импорты трёх модулей сохраняют изоляцию. Повторных нагрузочных замеров в этом ревью нет.
**GO для контролируемых изолированных preproduction-испытаний; production GO не выдан.**
Нужны согласованные SLO, лимиты ресурсов, полный dependency scan, нагрузка/soak,
наблюдение и rollback в целевом окружении. План и критерии — в `TRIALS.md`.

Предыдущее сравнение аллокаций в `perf/results.json` относится к JAR до этого ревью.
Новые JMX subscribers и изменение pool lifecycle в том измерении не участвовали;
проценты не переносятся автоматически на текущую сборку. На стенде следует измерять
именно JAR с зафиксированным hash из текущего `verification.json`.
