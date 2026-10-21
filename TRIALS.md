# Допуск и план испытаний

## Решение

Код допускается к контролируемым испытаниям на изолированном стенде после успешного
`clean build --rerun-tasks`. Безусловный production GO не выдан. Локальные unit/network
проверки уже исполняются сборкой; целевой стенд, production-профиль и SLO не предоставлены,
поэтому результаты нагрузочных, soak и deployment-испытаний здесь не выдумываются.

## Подготовка стенда

1. Зафиксировать JAR hash, JDK, runtime dependency graph, конфигурацию proxy/команд/Jetty,
   CPU, heap/direct-memory/container limits, FD limit, TLS termination и число origins.
   Воспроизводимая сборка: `./gradlew clean build --rerun-tasks`; runtime graph:
   `./gradlew :app:dependencies --configuration runtimeClasspath`.
2. До запуска определить целевые RPS/concurrency, размеры и смесь тел, длительность
   потоков, долю TLS и медленных клиентов. Записать критерии p99, error budget,
   максимального RSS/FD, shutdown budget и длительность soak. Значения задаёт владелец
   сервиса по реальному профилю; средние из локального benchmark их не заменяют.
3. Подключить сбор клиентских latency/status/body-integrity результатов, JVM GC/heap,
   direct memory, RSS, FD, соединений и приведённой ниже JMX-диагностики. Получить
   исходный уровень после прогрева. Cached pooled memory не обязана возвращаться к нулю.
4. Проверить allowlist upstream и входную аутентификацию, если она нужна среде.
   Выполнить SCA разрешённого dependency graph в принятом CI-процессе. Назначить
   ответственного за остановку испытаний/rollback и сохранить предыдущий артефакт.

## Диагностика

`spring.jmx.enabled=true` и `spring.jmx.unique-names=true` включены в приложении.
Удалённый JMX listener не создаётся. Локальный JDK management client может обнаружить
MBean по шаблону `dev.example.proxy:type=Diagnostics,*`; identity различается между
контекстами. Для контейнерного стенда используйте уже защищённый management collector
платформы. HTTP-порт прокси для административного API не используется.

| Сигнал | Интерпретация |
| --- | --- |
| `AcceptingTraffic`, `LocalReadiness`, `LocalLiveness` | Локальный lifecycle; не проверка доступности upstream. `/local=200` также не является readiness probe backend |
| `ActiveProxyRequests` | Реальные занятые permits, включая pending acquire и cleanup; не вычислять из admitted минус completed |
| `BulkheadAvailableCalls`, `BulkheadMaxCalls` | Допуск команды и удержание слота до окончания фактической операции |
| `BulkheadRejectedTotal`, `CircuitBreakerRejectedTotal` | Отказы до передачи запроса транспорту; они не входят в `ProxyRejectedTotal` |
| `CircuitBreakerState`, `CircuitBreakerWindow*`, `CircuitBreakerFailureRate` | Состояние/текущее окно; `-1` rate означает недостаточно samples, window counters могут сбрасываться |
| `TimeLimiterTimeoutsTotal`, `TimeLimiterErrorsTotal`, `TimeLimiterSuccessTotal` | События результатов TimeLimiter, отдельно от факта освобождения ресурсов |
| `ProxyCompletedTotal`, `ProxyCancelledTotal`, `ProxyFailedTotal` | Terminal outcomes; диагностический 5xx после commit не меняет уже отправленный HTTP status |
| `UploadedBytesTotal`, `DownloadedBytesTotal` | Учёт завершённых обменов; downloaded не является TCP ACK или доказательством получения клиентом |
| `MaxQueuedResponseBytes` | Исторический максимум payload очереди среди завершённых обменов, не текущий RSS/общий allocator footprint |
| `ExchangeDurationNanosTotal` | Сумма времени завершённых обменов с cleanup; не источник p99 |

Показания отдельных атрибутов не составляют атомарный snapshot. Не выключать JMX во
время испытания без равноценного источника метрик. `--spring.jmx.enabled=false` отключает
bean и подписки. Удалённый доступ при необходимости настраивается владельцем платформы
с её аутентификацией и сетевой политикой.

## Матрица

| Этап | Сценарии | Условие перехода |
| --- | --- | --- |
| Smoke/корректность | Методы, URI/query/повторные headers, binary/multipart, HEAD/204, разные размеры body, первая порция до окончания stream | Верные байты/метаданные; multipart остаётся непрозрачным потоком; нет агрегации полного тела |
| Номинальная/пиковая нагрузка | Реальная смесь HTTP/HTTPS, body sizes и origins; постепенное повышение concurrency | Согласованные throughput/p99/error budget и лимиты CPU/RSS/FD выдержаны |
| Backpressure/перегрузка | Медленные upload/download, заполнение BH/pool, tiny chunks, повторные client RST | Ограниченная память/очереди, отказ без ожидания semaphore, после cleanup оба admission восстанавливаются |
| Ошибки upstream | Connect/TLS failure, молчание до headers, 5xx, malformed/truncated body, idle TLS reuse | Нет успешного завершения усечённых тел; классификация соответствует политике; восстановление после fault |
| Stall после headers | Backend отправил headers/первую порцию и перестал отдавать body | Общий deadline завершает операцию, ресурсы освобождаются; НЕ требовать открытия CB от этого игнорируемого timeout |
| Recovery | Backend снова доступен после OPEN; HALF_OPEN probes | Успешные probes возвращают circuit в CLOSED; failed probes вновь защищают backend; нет утерянных permits |
| Shutdown/redeploy | Снять экземпляр с маршрутизации, отправить SIGTERM при активных upload/download и pending acquire | Grace/drain и последующий cleanup укладываются в согласованный бюджет; нет ложного успешного end marker, оставшихся sockets/loops |
| Soak | Длительная реальная нагрузка с периодическими fault/recovery и несколькими origins | Нет устойчивого неограниченного роста retained heap/direct/RSS/FD/pending; стабильны SLO и восстановление |

При остановке бюджет оркестратора должен покрывать Spring graceful drain, остановку
команд и транспорта, TLS close и остановку Netty loops. Число 5 секунд в вызове
shutdownGracefully ограничивает фазу loops, а не весь lifecycle; command timeout
также не является полным бюджетом SIGTERM. Локальный regression использует grace 500 ms,
два активных потока и ограниченный deadline теста; он не задаёт production значения.

## Жёсткие причины остановки

- Искажение данных или успешное завершение фактически усечённого ответа.
- Permit/буфер/соединение не освобождаются после подтверждённого завершения cleanup.
- Рост памяти/FD/очередей без стабилизации или выход за утверждённый лимит процесса.
- Необъяснённые ошибки, нарушение согласованного SLO, невозможность безопасно остановить
  экземпляр в предусмотренный бюджет.

Получение HTTP-ответа клиентом может предшествовать внутреннему cleanup. Проверять
возврат permits нужно в согласованный ограниченный срок, а не в тот же машинный такт.
Уже отправленный статус нельзя заменить при позднем timeout; клиент должен видеть
обрыв неполного тела. Автоматических retries streaming-тел нет.

## Отчёт и следующий допуск

Сохранить точную конфигурацию/hash, профиль и длительность, число запросов/байтов,
ошибки по причинам, p50/p95/p99, максимумы и графики heap/direct/RSS/FD, поведение
permits и CB, время drain/exit, обнаруженные отклонения и повторный прогон исправлений.
Только после прохождения согласованных критериев принимать решение об ограниченном
production canary с наблюдением и rollback. Расширение трафика — отдельное решение
по данным canary, а не автоматическое следствие зелёных unit-тестов.
