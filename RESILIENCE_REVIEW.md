# Независимое ревью после добавления Resilience4j

Дата: 20 октября 2026 года. Объект: `app`, `proxy`, `resilience`.
Пять ролей предыдущего аудита дополнены специалистом по Resilience4j.
Это независимые технические проходы агентов, а не внешняя сертификация.
Выводы ревью сверены с исходниками и регрессионными тестами.
Этот отчёт фиксирует шестиролевой проход с 115 тестами. Его выводы дополнительно
проверены седьмым рецензентом; новые находки, исправления и итоговый допуск описаны
в [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md).

## Роли и область проверки

| Роль | Проверено |
| --- | --- |
| HTTP и безопасность протокола | Отказы до I/O, владение HTTP-ответом, abort после commit, сохранение framing и URI-ограничений |
| Netty и конкурентность | Отмена, поздний acquire callback, физическое закрытие upstream, release в pool, admission и bounded cleanup |
| Servlet/Jetty | Передача владения request/response, async lifecycle, readiness последней записи, callbacks после отмены |
| SRE | Перегрузка, shutdown, таймеры, удержание слотов до cleanup, события Bulkhead и классификация отказов |
| Архитектор API и модулей | Независимость библиотек, публичные контракты, направление зависимостей, состав Gradle-артефактов |
| Resilience4j | Реальное поведение CompletionStage TimeLimiter 2.4.0, cancellation, semaphore Bulkhead, CircuitBreaker и HALF_OPEN |

## Найденные и исправленные дефекты

P1 — нарушение корректности или изоляции запросов; P2 — неверное поведение
на конкретном граничном сценарии. Это внутренний приоритет, не оценка CVSS.

| Приоритет | Дефект | Исправление | Регрессия |
| --- | --- | --- | --- |
| P1 | Непригодный HTTPS-канал возвращался в pool сразу после вызова `close()`. Во время асинхронного TLS close_notify канал ещё мог быть active и попасть следующему запросу | `pool.release()` для непригодного канала выполняется после `channel.closeFuture()`. При ошибке release также ожидается физическое закрытие. Admission и completion удерживаются до завершения всей цепочки | `nonReusableChannelIsNotReturnedWhileTlsCloseStillLeavesTheTransportActive`, `failedPoolReleaseAlsoWaitsForPhysicalTransportClose` |
| P2 | Shutdown между проверкой состояния и постановкой TimeLimiter timer мог превратить локальную отмену в `RejectedExecutionException`, HTTP 500 и ложный failure CircuitBreaker | Сохраняется уже завершённый сигнал отмены; неожиданно недоступный scheduler классифицируется как `CommandNotExecutedException` до запуска команды | `closeBetweenDeadlineCheckAndSchedulingRemainsAnIgnoredCancellation`, `unavailableSchedulerIsALocalRejectionRatherThanUpstreamFailure` |
| P2 | Возврат Bulkhead permit через `releasePermission()` не публиковал `CALL_FINISHED` для исполненной команды | Для начатой команды используется `onComplete()` после cleanup; неиспользованное разрешение возвращается без события завершённого вызова | `timeoutCancelsTaskButKeepsSlotUntilActualCleanup` проверяет отсутствие события до cleanup и ровно одно после него |

Исправлена также разорванная таблица модулей в README.
Подтверждённых незакрытых P1/P2 в рассмотренном изменении не осталось.

## Дополнительные проверенные сценарии

- Timeout завершает `result()` и запрашивает отмену I/O, но Bulkhead остаётся занят
  до `CommandTask.completion()`. Получение заголовков и части тела не завершает команду.
- Отмена во время короткого `start()` передаётся опубликованной задаче ровно один раз.
  Shutdown прекращает admission и отменяет активные команды.
- TimeLimiter не меняет future фактической очистки задачи. Таймер удаляется как при
  успехе, так и при раннем exceptional completion; завершённые запросы не удерживаются
  очередью scheduler до deadline.
- Отмена клиентом, локальный отказ и общий deadline игнорируются CircuitBreaker,
  не считаются успешными samples. В `HALF_OPEN` игнорируемая отмена или timeout
  возвращает probe permission; следующий успешный probe закрывает breaker.
- Ошибки upstream открывают CircuitBreaker; OPEN и заполненный Bulkhead возвращают
  503 без обращения к proxy/upstream. Обычные upstream 4xx не открывают breaker.
- После commit TimeLimiter прерывает неполный ответ; код не пытается отправить
  второй HTTP-статус или корректный конец усечённого body.
- Завершившийся acquire future с ещё не выполненным listener не освобождает admission.
  Поздно полученный канал проходит cleanup перед завершением `ProxyCall`.
- Публичные CompletionStage доступны только через read-only представление;
  отмена отдельного `toCompletableFuture()` не подменяет отмену сетевой операции.

Сетевые сценарии находятся в `ProxyResilienceIntegrationTest` и используют настоящий
Jetty и локальный upstream. Гонки cleanup проверяются детерминированно управляемыми
promises в `BackpressureTest`; для этого не нужны случайные сетевые задержки.
`CommandExecutorTest` проверяет библиотеку команд отдельно от HTTP и Spring.

## Границы гарантий

`ProxyCall.completion()` означает освобождение ресурсов upstream и попытку
`AsyncContext.complete()`/abort. Это не подтверждение TCP ACK, доставки клиенту
или фактического callback `AsyncListener.onComplete`.

Команда, Processor, cancel и подписчики событий должны быть неблокирующими.
TimeLimiter не может безопасно прервать произвольный зависший синхронный код.
При нарушении контракта cleanup слот остаётся занят: возвращать его при ещё живой
операции означало бы снять ограничение нагрузки.

В app один CircuitBreaker защищает общую логическую группу `/proxy`.
Для независимых backend-групп нужны отдельные ограниченные профили executor в app.
Автоматические retries для потокового тела не включены.

Результат полной сборки и точные количества тестов записаны в `verification.json`.
Юнит- и интеграционные тесты не заменяют нагрузочные и soak-испытания в целевом окружении.
