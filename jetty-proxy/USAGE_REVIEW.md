# Совет экспертов: сценарии, интеграция и производительность

**Статус:** замечания R1–R10 исправлены в runtime `46db231`;
[исправления и проверка](FIXES.md). Ниже сохранён исходный анализ snapshot до
исправлений; его benchmarks и probes не являются результатами новой сборки.

Дата: 26 октября 2026. Проверен HEAD `294f548`, runtime `65a1f9e`.
Шесть независимых направлений: архитектура/API, интеграция, HTTP, lifecycle,
производительность, эксплуатация. Эксперты выполняли перекрёстное ревью, включая
компоненты, которые они не реализовывали. Production-код в рамках ревью не менялся.

## Вывод

Для прозрачного проксирования через Jetty EE11 библиотека уже пригодна к
подключению: обычные JAR, явная Spring-конфигурация, application-owned Filter,
сохранение wrappers, streaming/backpressure и отдельный bounded fetch.
Она ещё не является полностью взаимозаменяемой адаптацией портального pipeline.
Перед таким переходом нужно исправить обнаруженные ошибки интеграции и cleanup,
а для большого количества замен ссылок — изменить организацию output-записей
и ввести отдельный бюджет памяти rewrite.

Ранее пройденные 553 теста подтверждают покрытые сценарии. Новые probes показывают
границы покрытия: две дополнительные проверки lifecycle намеренно утверждают
текущее ошибочное состояние; их PASS означает воспроизведение дефекта.

## Сценарии использования

| Сценарий | Оценка | Что требуется от приложения |
| --- | --- | --- |
| GET/binary, один backend, одна TLS identity | Подключение небольшое; streaming проверен | Trusted target, один shared client/engine, Filter и lifecycle |
| Multipart POST/PUT, form PUT | Передача байтов проверена | Не читать body и не запускать body-consuming filters до handoff |
| Дополнительные обычные headers | Удобный API | Global/per-plan customizer; быстрый неблокирующий callback |
| Внешний cookie/SSO storage | Есть async stages | Ограниченный executor и подготовленные snapshots приложения |
| Несколько backend, одна TLS identity | Новый core поддерживает | Resolver, quotas и политики backend; legacy adapter пока fixed-backend |
| Несколько TLS identities / ротация | Возможно отдельными clients/engines | Registry, версия профиля, закрытие старых экземпляров, общий бюджет ресурсов |
| Spring-приложение с существующим HttpClient | Найдена ловушка подключения | Исправить default autowire candidate или явно назначить host Primary |
| HTML/CSS/WCS rewrite | Требует доработок перед точной legacy-заменой | Совместимый selector, output coalescing, отдельный rewrite budget |
| Синхронная загрузка ресурса | Fetch пригоден, host HTTP-facade требует исправлений | Общие проверки доступа, собственная HTTP-политика, headers/cookies, text limits |
| Произвольный Servlet-контейнер / Boot 3 / иной Jetty | Не подтверждено | Проверенный стек: Java 21, Servlet 6.1, Jetty EE11 12.1.12; Spring adapter Boot 4.1.1 |

## Подтверждённые ошибки и несовпадения интеграции

Все пункты ниже — **P2**: значимы для соответствующего сценария, но не означают,
что обычный factory-based streaming постоянно работает неправильно.

### R1. Исключение при release оставляет completion и admission занятыми

[JettyProxyEngine.java](src/main/java/dev/example/jettyclient/proxy/JettyProxyEngine.java#L523),
строки 523–530, 728–739, 920–922, 1067.
Upload callback вызывает `owner.release()` до сброса `uploadPending`; download
callback вызывает `release(chunk)` до сброса `writePending`, а retained counter
уменьшается только после `chunk.release()`. Если releaser бросает recoverable
RuntimeException, обмен становится terminal, но барьер cleanup уже не закрывается.

**Воспроизведено** для upload и download с контролируемым ошибочным releaser:
upstream завершён, body передан, `activeRequests()==1`, completion не завершён,
pending-флаг остаётся true. При download также остаётся `retainedChunks==1`.
Это исключительный путь, не наблюдение отказа стандартного pool при обычной нагрузке.

Исправление: обновлять ownership flags/counters в `finally`, сохранять ошибку
освобождения в результате, не повторять release. Ошибка pool не должна навсегда
удерживать admission. Исходник probe находится в `review-evidence`.

### R2. Spring import нарушает существующую unqualified-инъекцию HttpClient

[JettyProxyClientConfiguration.java:28](../jetty-proxy-spring/src/main/java/dev/example/jettyclient/proxy/JettyProxyClientConfiguration.java#L28).
Library-owned client является обычным default candidate. Если host уже имеет
свой `HttpClient` и consumer с аргументом `HttpClient client`, после импорта
появляются два кандидата. Внутренние qualifiers защищают библиотеку, но не host.

**Воспроизведено** настоящим Spring context: `NoUniqueBeanDefinitionException`.
Рекомендация — `@Bean(defaultCandidate=false)` для библиотечного клиента при
сохранении явных qualifiers; добавить тест существующего unqualified consumer.

### R3. Resource endpoint обходит общую policy приложения

[HttpProxyFilter.java:46](../jetty-proxy-integration-example/src/main/java/dev/example/integrationhost/HttpProxyFilter.java#L46),
[ResourceController.java:26](../jetty-proxy-integration-example/src/main/java/dev/example/integrationhost/ResourceController.java#L26).
`/backend` проверяет forbidden, а `/resource` обращается к тому же backend напрямую.

**Воспроизведено HTTP:** `/backend/forbidden` → 403, upstream не вызван;
`/resource?path=%2Fforbidden` → 200, возвращено тело того же ресурса.
Это дефект host-примера, не проверок target в core: разрешения определяет приложение.
Общую access/backend policy следует применять до обоих входов.

### R4. Resource endpoint смешивает статус backend и metadata нового ответа

[ResourceController.java:28](../jetty-proxy-integration-example/src/main/java/dev/example/integrationhost/ResourceController.java#L28).
Все входные headers, включая Range, передаются loader; наружу сохраняются статус
backend и только Set-Cookie, а тело преобразуется в строку и UTF-8.

**Воспроизведено HTTP:** Range `bytes=0-1` → статус 206, body `ab`, но обязательный
Content-Range отсутствует. По коду аналогично теряются WWW-Authenticate у 401,
Allow у 405 и Location у redirect. Core fetch сохраняет эти headers правильно.

Нужна явная политика facade: загрузка целого текстового ресурса с собственными
статусами и allowlist request headers либо корректное сохранение семантики
проксируемого ответа. Простое удаление Range не решает все статусные случаи.

### R5. Legacy handler игнорирует динамический target и timeout

[LegacyProxyHandler.java:16](../jetty-proxy-integration-example/src/main/java/dev/example/integrationhost/LegacyProxyHandler.java#L16)
использует только rawPath; единственный Netty engine настроен при запуске.
Jetty handler использует `BackendRoute.target()` и `timeout()`.
При route на другой backend или с другим сроком выбор реализации меняет результат.
**Подтверждено по коду**; текущий FixedResolver и тесты проверяют только один backend.

Нужен выбор legacy engine по backend/profile или fail-fast ограничение адаптера.
До этого seam подходит для будущего toggle, но динамические routes не эквивалентны.

### R6. Не перенесён legacy selector CSS/WCS по пути

[LinkRewritePolicy.java:56](src/main/java/dev/example/jettyclient/proxy/policies/LinkRewritePolicy.java#L56)
выбирает rewrite по MIME; старый
[ProxyCommandSupport.java:197](../examples/proxy/ProxyCommandSupport.java#L197)
также выбирает `.css`/`.wcs` независимо от MIME.

**Воспроизведено API:** `/skin.wcs` с `application/octet-stream` даёт `select()==null`.
В старой логике тот же asset выбирается для rewrite. Для ZK/WCS это может оставить
backend URLs неизменёнными. Нужен opt-in legacy selector; автоматически трактовать
любой binary ответ как текст не следует.

### R7. Arbitrary supplied HttpClient не эквивалентен фабричному

[JettyProxyEngine.java:410](src/main/java/dev/example/jettyclient/proxy/JettyProxyEngine.java#L410)
передаёт исходный method в `client.newRequest(...).method(...)`.
**Воспроизведено API:** обычный Jetty HttpClient меняет `head` в `HEAD`, специальный
клиент фабрики сохраняет `head`. HTTP method case-sensitive; меняется операция и
семантика её тела. Factory-based путь этой проблемы не имеет.

Для custom networking нужен публичный pre-start factory customizer/transport hook,
сохраняющий специальный request implementation, либо явный проверяемый контракт
совместимого клиента. Один параметр конструктора HttpClient такой совместимости
не гарантирует.

### R8. Maven POM нарушает заявленный host-provided Servlet API

[build.gradle:7](build.gradle#L7) использует `compileOnlyApi`, однако generated POM
публикует `jakarta.servlet-api` как `compile`; Maven/POM consumers получают её
транзитивно и в runtime. Gradle `runtimeElements` эту зависимость исключает.
**Проверены обе опубликованные модели.** Нынешний published-consumer явно добавляет
Servlet API и поэтому не обнаруживает различие.

Рекомендация — согласовать POM scope с provided-контрактом и отдельно проверять
runtime dependency shape, включая внешнего Maven/POM consumer.

## Производительность

### Новый замер текущих артефактов

8 МиБ identity GET, 20 клиентов, 4 CPU на весь стенд, JDK 21.0.9+10,
`-Xms256m -Xmx1g`, порции 64 КиБ. Два I/O loop Netty / два selector Jetty,
номинально 64 соединения на origin, maxConcurrent 128. Прогрев по 5 секунд;
измеряемые фазы по 8 секунд: direct, Netty, Jetty, Jetty, Netty, direct.
Генератор JMeter, Python upstream и оба JVM находятся на одной машине.

| Вариант | RPS | CPU прокси, мс/запрос | p95, мс | Ошибки |
| --- | ---: | ---: | ---: | ---: |
| Jetty + Netty, app | 154,04 | 9,598 | 209 | 0 |
| Jetty Client, demo | 175,15 | 5,775 | 168 | 0 |

В этом прогоне Jetty Client дал +13,7% RPS и −39,8% CPU/request. Отдельные фазы:
Netty 152,38 / 155,71; Jetty 170,50 / 179,79. Каждый запрос проверялся по статусу
и длине; полные SHA-256 проверялись до и после каждой фазы. Замер относится именно
к текущим SHA JAR из [streaming-benchmark.json](review-evidence/streaming-benchmark.json).

Это сравнение целых приложений: Netty app включает command/Resilience4j, Jetty
demo такого слоя не имеет. Прогрев короткий, независимых JVM forks и доверительных
интервалов нет; обе реализации ускорились во второй фазе. Нельзя приписать всю
разницу удалению моста или считать число production capacity.

Direct дал 208,50 RPS, но p99 около 1,08 с. Python fixture использует backlog 5
при 20 клиентах; это возможный вклад в хвост задержек, причинность отдельно не
измерялась. Такой direct-run нельзя использовать как достоверный потолок стенда.
Этот прогон не измеряет rewrite, TLS, multipart throughput или slow-client нагрузку.

### R9. Много замен создаёт слишком много отдельных writer calls — P2

[JettyProxyEngine.java:854](src/main/java/dev/example/jettyclient/proxy/JettyProxyEngine.java#L854)
вызывает writer отдельно для каждого сегмента output, ограничивая размер одной
порции, но не объединяя соседние малые сегменты.

**Проверено на текущем JAR**, 8 МиБ CSS, input из 128 direct-сегментов по 64 КиБ:

| Замен URL | Output-сегментов / вызовов writer по алгоритму engine | Сегментов <1 КиБ |
| ---: | ---: | ---: |
| 0 | 128 | 0 |
| 40 | 208 | 80 |
| 10 000 | 20 124 | 20 002 |

SHA-256 результата во всех случаях совпал с эталоном. Это число вызовов writer,
не syscalls и не измеренный rewrite RPS. Но оно подтверждает увеличение числа
callbacks и работы очередей. Для wrappers к нему добавляются heap copy и flush.
Рекомендация — объединять уже доступные малые output-сегменты в порции до
chunkBytes, сохраняя большие borrowed views. Сначала проверить SHA/ownership,
затем сравнить writes, allocations и throughput на одинаковых телах.

### R10. Per-request rewrite limit не ограничивает суммарную память — P2

Два rewrite worker и очередь до общего maxConcurrent заданы в
[JettyProxyEngine.java:88](src/main/java/dev/example/jettyclient/proxy/JettyProxyEngine.java#L88).
Полные input тела уже могут ожидать worker; input удерживается и при медленной
выдаче результата. 128 запросов × 8 МиБ = **1 ГиБ только полезных input slabs**.
Если пользовательский rewriter создаёт независимый output до 12 МиБ, полезные
input+output могут достигать 2,5 ГиБ. Это расчёт по коду, не измеренный OOM/RSS.

Нужны отдельные rewrite admission/byte budget и настройки worker/queue, чтобы
не уменьшать concurrency обычного streaming ради преобразуемых ответов.
В отличие от rewrite, обычный native streaming не накапливает целое тело.

Fetch имеет самостоятельный бюджет 8/2/8, но делает direct → heap wire copy;
gzip/deflate добавляют decoded storage, а bodyAsString — builder и строку.
При wire limit 8 МиБ восемь admitted fetch уже допускают до 64 МиБ входных slabs
плюс heap-копии. После completion результаты и строки удерживает приложение;
admission fetch их больше не ограничивает. Отдельные admission streaming/fetch
также не обеспечивают отдельных connection pools: длинные streaming-запросы
могут задерживать loader к тому же destination.

## Удобство интеграции и эксплуатация

- Global Spring header customizers подключены только к streaming engine. Fetch
  требует отдельной подготовки headers и post-fetch policy. Для общего SSO/cookie/
  request-id поведения нужны единый прикладной facade или дополнительные адаптеры.
- Stopped/client-executor-missing/admission-full дают одинаковый ранний 503 и
  generic IOException. Для circuit breaker и метрик нужны стабильные failure
  reason/domain/phase; client cancellation не стоит считать отказом backend.
- Fetch text/charset errors возникают в `bodyAsString()` после успешного fetch
  completion. README обобщает все limits как exceptional completion слишком широко.
  Метрики текстового loader должны включать преобразование в строку.
- Fetch result не предоставляет elapsed/wire byte counts после decode; для настройки
  budgets полезен отдельный observation/result metadata. Несколько TLS clients
  также стоит маркировать instance/profile names: сейчас имена workers одинаковы.
- Несколько TLS identities умножают client pools, admission и worker budgets.
  Библиотека не предоставляет общий registry/лимит приложения; это явная работа host.

Отдельно оставлены **неподтверждённые** кандидаты для последующей проверки:
произвольный executor, отбрасывающий принятый drain при shutdown, и поведение
fetch при fatal JVM errors. Они не включены в подтверждённые дефекты выше.

## Рекомендуемый порядок доработок

1. Закрыть cleanup при исключении release и Spring candidate conflict; добавить
   проверки желаемого поведения в штатный suite.
2. Исправить общий guard и HTTP-policy loader; сделать legacy routing явным и
   согласовать CSS/WCS selector. Это необходимая точность адаптации к порталу.
3. Согласовать Maven provided scope и контракт custom HttpClient; подготовить
   единый прикладной слой policies для streaming/includes.
4. Добавить coalescing rewrite output и отдельный byte budget; только затем
   сравнивать rewrite throughput/allocations, включая wrappers и медленного клиента.
5. Добавить типизированные причины отказа и observation hooks для эксплуатации.

## Материалы проверки

- [Сводка probes](review-evidence/probes.json): release failures, Spring ambiguity,
  method case, CSS/WCS, фрагментация и опубликованные dependency models.
- [Реальные host HTTP ответы](review-evidence/host-probes.json).
- [Полные условия и фазы GET benchmark](review-evidence/streaming-benchmark.json).
- Исходники точечных probes лежат рядом; они не включены в Gradle source sets.
  `CouncilReleaseFailureProbeTest` использует существующий test helper и намеренно
  проверяет ошибочное состояние; это evidence, а не готовый regression gate.
- Raw logs/JTL: `/tmp/jetty-library-council-bench/`; временные probes и отдельный
  Gradle init: `/tmp/jetty-library-council-probes/`. Обычный suite не переписывался.
