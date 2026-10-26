# Проверка подключения к логике приложения

Основа: `master` с примером `examples/proxy/` из коммита `9794b52`.
Реализация: `e078071`. Пять специалистов проверили архитектуру подключения,
Servlet/Jetty I/O, lifecycle, headers/cookies и ограничения памяти/CPU.

## Результат

Вход через `JettyProxyFilter` отделён от транспорта `JettyProxyEngine`.
`ProxyRequestPlan` передаёт выбранный URI, индивидуальный deadline, расширения
заголовков и optional body policy. `ProxyExchangeExecutor` оставляет место для
будущего выбора транспорта; feature toggle не реализован.

Два способа подключения проверены: engine сам начинает async либо принимает
`AsyncContext`, созданный приложением для фактических request/response wrappers.
`ProxyCall` возвращает однократный результат после cleanup и принимает отмену.

Streaming остаётся основным путём. Выбранное преобразование ответа использует
ограниченные direct slabs и отдельный bounded executor. Вход копируется в slabs
только в этой ветке; результат может заимствовать их срезы. Полные heap-массивы
или строки не требуются API.

## Что дополнительно исправлено при ревью

- Upstream `Set-Cookie` сохраняет cookies, уже выставленные приложением.
- Проверка принадлежности supplied async выполняется до любого отказа;
  чужой context не завершается.
- `Cache-Control: no-transform` исходного запроса и ответа запрещает rewrite.
- Число output-сегментов проверяется до создания их снимков.
- Ошибка до запуска worker не оставляет установленный флаг удержания памяти.
- Отменённые queued rewrite-задачи пропускают пользовательское преобразование.
- После commit результат сохраняет HTTP-статус; ошибка тела передаётся отдельно.
- Вход worker удерживается до его фактического завершения, включая timeout.

## Проверки

`gradle build`: **395 тестов**, из них **150** в `jetty-client-example`;
failures/errors/skips — ноль. В этом прогоне app и command-support также
пересобраны после изменений, пришедших с `master`.

Реальные HTTP-проверки включают замену default planner, context path/raw URI,
multipart POST/PUT с побайтным сравнением, сохранение cookies и wrappers,
supplied async, streaming до окончания upstream, входные/выходные лимиты rewrite,
ошибку преобразования и timeout с продолжающим работать worker. Сохраняются
предыдущие проверки GET 8 МиБ, tiny chunks, раннего ответа и shutdown.

Проверенный executable JAR:
`49efd4bd5d8e3d1341a422fbc0736b8efd374a29e46cd8f85b82db353748c737` (SHA-256).

Короткий контроль streaming GET 8 МиБ: 20 клиентов, прогрев по 5 секунд,
A/B/B/A по 8 секунд, одинаковые JVM/selector/chunk настройки. До адаптации
получено 159,96 rps, после — 176,83; CPU 6,431 → 5,663 мс/запрос,
p95/p99 177/224 → 161/215 мс. Все 6 521 запрос, включая прогрев, прошли
HTTP/size проверки; SHA-256 до/после фаз совпал. Грубой регрессии не видно,
устойчивое ускорение этим не доказано: генератор и приложения делят 4 CPU,
прогрев короткий, независимых forks нет. Подробные параметры, SHA JAR и результаты:
[filter-integration-performance.json](filter-integration-performance.json).

## Границы

Это готовая граница транспорта для подключения прикладных политик, а не перенос
всего портала. HTML/CSS algorithms на Netty `ByteBuf`, response cache,
синхронный `HttpProxy.load()`, профильные TLS credentials, Security и Resilience4j
не включены автоматически. Карта необходимых изменений и примеры находятся
в [MIGRATION.md](MIGRATION.md).

Неблокирующий I/O не означает zero-copy. Completion handlers и header policies
должны быть быстрыми; blocking storage не выполняется на владельце обмена.
Произвольный rewriter нельзя принудительно остановить по deadline: память и
admission удерживаются до его возврата. Ограничение GET reset до первых headers
из предыдущего строгого ревью также сохраняется.
