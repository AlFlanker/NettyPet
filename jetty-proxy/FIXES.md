# Исправления замечаний совета

Runtime: `46db2315298eb39bd19595276255db786560a42d`, 26 октября 2026.
Исходный список и измерения до исправлений: [USAGE_REVIEW.md](USAGE_REVIEW.md).

| Замечание | Исправление | Проверка |
| --- | --- | --- |
| R1: зависание cleanup при release failure | Ownership flags/counters сбрасываются даже при исключении; primary failure сохраняется, cleanup добавляется suppressed, release не повторяется. Отмена остальных ресурсов продолжается | Upload, download, pending chunk и batch release failures; completion завершён, activeRequests/budget/pool возвращены |
| R2: Spring HttpClient ambiguity | Owned client имеет `defaultCandidate=false`, внутренние consumers остаются qualified | Настоящий host context и внешний consumer с прежней unqualified-инъекцией |
| R3: обход общей policy через resource endpoint | Один Filter применяет maintenance/backend/forbidden до обоих входов; include получает проверенный immutable route | Forbidden/encoded paths и maintenance, отсутствие upstream call/body read до решения |
| R4: некорректная metadata loader | `/resource` — full text facade: allowlist request headers, собственный UTF-8 200 только для upstream 200; остальные upstream statuses →502 | Range/условные headers исключены; origin 206/401/405/302, отсутствие повторного redirect запроса |
| R5: потеря dynamic target/timeout старым handler | Fixed-backend adapter до handoff возвращает 501 для неподдерживаемого route | Новый handler использует target/timeout; старый явно отказывает без body/async/upstream |
| R6: потеря CSS/WCS selector | Добавлен opt-in `LinkRewritePolicy.legacyCompatible(settings)` с прежними path/MIME правилами | 18 selector/charset/binary/negotiation регрессий; default MIME selector сохранён |
| R7: supplied client меняет регистр method | Engine/fetch требуют factory-created client до создания workers; добавлен callback настройки фабрики до start | Обычный client отклоняется; extension method `head` сохранён на wire; настройки executor/name и proxy controls проверены |
| R8: Servlet API попадает в Maven runtime | Generated POM использует provided; Gradle runtime graph также исключает API | Независимые API/runtime graphs обоих metadata режимов до добавления host Servlet API |
| R9: amplification малых rewrite writes | Готовые соседние малые spans объединяются в bounded pooled portions; крупные borrowed spans и их хвосты сохраняются | Реальный policy, 8 МиБ CSS и 10 000 ссылок: 20 124 →142 записи, SHA совпадает; slow/cancel/cleanup tests |
| R10: общий rewrite memory budget отсутствует | `ProxyRewriteOptions`, defaults 2 workers / queue 8 / 128 МиБ; резерв declared input/output +chunk до накопления | Exhaustion/overflow/queue дают503; обычный streaming продолжает работать; резерв удерживается до последнего worker/write cleanup |

Дополнительно подтверждено и исправлено сокрытие fatal errors в fetch.
VirtualMachineError/ThreadDeath повторно выбрасываются после terminal completion
и доступного cleanup; blocking adapter не превращает их в обычный IOException.
Добавлены 7 проверок constructor/fatal start/callback/blocking сценариев.

## Изменения при подключении

- Для engine/fetch используйте `JettyProxyClientFactory.createStarted(...)`.
  Настройки сети/executor можно передать через третий аргумент
  `createStarted(properties, tlsProfile, Consumer<HttpClient>)`. Callback вызывается
  один раз до start; фабрика восстанавливает обязательные proxy controls.
- Spring поддерживает именованный bean `jettyProxyHttpClientCustomizer`.
  Для доступа к библиотечному HttpClient нужен явный qualifier; прежняя инъекция
  клиента host продолжает работать.
- Настройки rewrite: `jetty.proxy.rewrite.workers`, `max-queued`,
  `max-buffered-bytes` (DataSize, default `128MB`). Для plain core — четвёртый
  аргумент Engine, `ProxyRewriteOptions`.
- На каждый выбранный rewrite резервируется
  `maxInputBytes + maxOutputBytes + chunkBytes`. Даже маленький фактический ответ
  расходует заявленный резерв до cleanup. Это бюджет payload limits, не RSS;
  allocator rounding, metadata, TLS и временные String-копии учитываются отдельно.
- Portal migration example явно использует `legacyCompatible(...)`. Default
  selector остаётся MIME-based HTML/CSS/JS; точный legacy selector пропускает JS-only
  MIME, как старый код, если путь не CSS/WCS.
- Query `path` у `/resource` разбирается Filter без Servlet parameter/body parsing.
  Дублирующийся или отсутствующий query path даёт400. Body-only path у POST
  не принимается. Дальнейший MVC получает уже проверенный route через attribute.
- Старый host adapter намеренно остаётся fixed-backend. Проверка capability
  устраняет молчаливую отправку не в тот backend; она не реализует dynamic legacy
  engine registry. Реальный старый pipeline портал оборачивает своим handler.

## Результат проверки

Полная `gradle build`: **613 тестов, 0 failures/errors/skips**:

| Модуль | Тесты |
| --- | ---: |
| `jetty-proxy` | 221 |
| `jetty-proxy-spring` | 7 |
| `jetty-client-example` | 104 |
| `jetty-proxy-integration-example` | 36 |
| `app` / `proxy` / `command-support` | 74 / 87 / 84 |

Gradle переиспользовал неизменённые задачи; изменённые Jetty-модули проверены с
окончательными production sources. Финальный test-only сценарий добавил точное
воспроизведение 8 МиБ / 10 000 ссылок. Внешний consumer успешно проверен в режимах
Gradle module metadata и Maven POM only; API предоставляет host в обоих режимах.

Для исходного rewrite-сценария проверены **142 фактических write-вызова**,
SHA-256 ожидаемого результата, `reservedRewriteBytes()==0`, `poolOutstanding==0`
и отсутствие активного обмена после completion. Это functional write count,
не новый RPS benchmark. Измерение 175,15/154,04 RPS из предыдущего совета относится
к артефактам до этих исправлений и не переносится на новую сборку.

Полные counters, команды, runtime commit и SHA-256 четырёх JAR:
[fix-verification.json](fix-verification.json).

## Оставшиеся улучшения

Типизированные причины отказов для telemetry/circuit breaker, подробные fetch
observations и instance/profile labels остаются отдельными улучшениями API.
Документация уточнена: text/charset limit проверяется в `bodyAsString()` после
transport completion; метрика loader должна включать эту конверсию. Бюджет
сохранённых результатов, строк и кэша принадлежит приложению. Выделенные admission
streaming/fetch по-прежнему используют общий connection pool.

Feature toggle, Spring Security и перенос закрытого портала в эти исправления
не входят. Поддерживаемый стек и ограничения протоколов описаны в README.
