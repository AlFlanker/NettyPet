package com.otr.portal.command.execution;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Исполнитель коротких неблокирующих ресурсных команд.
 * Bulkhead ограничивает незавершённые операции, включая очистку после таймаута;
 * CircuitBreaker наблюдает результат с учётом TimeLimiter. Классификацию ошибок задаёт приложение.
 * Методы start(), cancel(), обработчики событий и подписчики CompletionStage должны быть неблокирующими.
 */
public final class CommandExecutor<T> implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(CommandExecutor.class.getName());
    private final Object lifecycle = new Object();
    private final Set<Execution> active = ConcurrentHashMap.newKeySet();
    private final Bulkhead bulkhead;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final ScheduledThreadPoolExecutor scheduler;
    private final boolean ownsScheduler;
    private boolean closed;

    /**
     * Создаёт самостоятельные политики и планировщик для одной логической группы команд.
     * Исполнитель владеет этим планировщиком и останавливает его в close().
     */
    public CommandExecutor(String name, BulkheadConfig bulkheadConfig,
                           TimeLimiterConfig timeLimiterConfig, CircuitBreakerConfig circuitBreakerConfig) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bulkheadConfig, "bulkheadConfig");
        Objects.requireNonNull(timeLimiterConfig, "timeLimiterConfig");
        Objects.requireNonNull(circuitBreakerConfig, "circuitBreakerConfig");
        if (!bulkheadConfig.getMaxWaitDuration().isZero()) {
            throw new IllegalArgumentException("Bulkhead maxWaitDuration must be zero");
        }
        CommandScope.validateTimeout(timeLimiterConfig.getTimeoutDuration());
        this.bulkhead = Bulkhead.of(name, bulkheadConfig);
        this.circuitBreaker = CircuitBreaker.of(name, circuitBreakerConfig);
        this.timeLimiter = TimeLimiter.of(name, timeLimiterConfig);
        this.scheduler = newScheduler("command-timeout-" + name);
        this.ownsScheduler = true;
    }

    /**
     * Использует фактические политики реестра и общий планировщик.
     * Закрытие этого исполнителя не останавливает переданный планировщик: им управляет его владелец.
     */
    public CommandExecutor(Bulkhead bulkhead, TimeLimiter timeLimiter,
                           CircuitBreaker circuitBreaker, ScheduledThreadPoolExecutor scheduler) {
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead");
        this.timeLimiter = Objects.requireNonNull(timeLimiter, "timeLimiter");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = false;
        if (!bulkhead.getBulkheadConfig().getMaxWaitDuration().isZero())
            throw new IllegalArgumentException("Bulkhead maxWaitDuration must be zero");
        CommandScope.validateTimeout(timeLimiter.getTimeLimiterConfig().getTimeoutDuration());
    }

    /**
     * Создаёт однопоточный планировщик сроков без сохранения отменённых таймеров в очереди.
     * Задачи в нём должны быть короткими: пользовательские повторные запуски выполняются отдельно.
     */
    public static ScheduledThreadPoolExecutor newScheduler(String threadName) {
        var scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    /**
     * Возвращается после start() либо после отказа до запуска. Ожидания результата нет.
     * Отсчёт общего срока начинается до start(). Если отмена возникла во время запуска,
     * её сигнал передаётся задаче ровно один раз после получения объекта CommandTask.
     * При Error до возврата задачи слот освобождается, а ошибка пробрасывается.
     */
    public CommandExecution<T> execute(CommandAction<T> command) {
        return execute(command, ignored -> { });
    }

    /** Подключает наблюдателя до допуска и запуска, чтобы он увидел и синхронный сбой старта. */
    public CommandExecution<T> execute(CommandAction<T> command, Consumer<CommandExecution<T>> observer) {
        Objects.requireNonNull(command, "command");
        return executeScoped(ignored -> command.start(), observer);
    }

    /** Запускает действие, которому нужен общий срок и состояние отмены, без дополнительного наблюдателя. */
    public CommandExecution<T> executeScoped(ScopedCommandAction<T> command) {
        return executeScoped(command, ignored -> { });
    }

    /**
     * Создаёт общий бюджет и состояние отмены до допуска операции.
     * Передаёт это состояние действию до первой попытки, чтобы отмена не зависела от публикации задачи.
     */
    public CommandExecution<T> executeScoped(ScopedCommandAction<T> command, Consumer<CommandExecution<T>> observer) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(observer, "observer");
        Execution execution = new Execution();
        observer.accept(execution);
        synchronized (lifecycle) {
            if (closed) {
                execution.reject(new CommandNotExecutedException("Command executor is closed"));
                return execution;
            }
            if (!bulkhead.tryAcquirePermission()) {
                execution.reject(BulkheadFullException.createBulkheadFullException(bulkhead));
                return execution;
            }
            // Обработчик допуска Bulkhead может синхронно закрыть исполнитель.
            if (closed) {
                bulkhead.releasePermission();
                execution.reject(new CommandNotExecutedException("Command executor is closed"));
                return execution;
            }
            execution.permit = true;
            active.add(execution);
        }

        try {
            Supplier<CompletionStage<T>> limited = () -> {
                if (execution.signal.isDone()) return execution.signal;
                CompletionStage<T> observed = execution.signal.whenComplete((value, failure) -> {
                    execution.timer.cancelTimeout();
                    if (failure == null) timeLimiter.onSuccess();
                    else timeLimiter.onError(unwrap(failure));
                });
                try {
                    execution.timer.arm();
                } catch (RejectedExecutionException schedulingFailure) {
                    // Между проверкой и постановкой таймера close() мог уже зафиксировать отмену.
                    if (!execution.signal.isDone()) {
                        execution.completeSignal(null, new CommandNotExecutedException(
                                "Command timeout scheduler is unavailable", schedulingFailure));
                        execution.requestCancellation(CancellationReason.SHUTDOWN);
                    }
                }
                return observed;
            };
            CircuitBreaker.decorateCompletionStage(circuitBreaker, limited).get()
                    .whenComplete(execution::publishResult);
            if (!execution.beginStart()) {
                execution.finishResources();
                return execution;
            }
            CommandTask<T> task;
            try {
                task = Objects.requireNonNull(command.start(execution.scope), "Command.start() returned null");
            } catch (Exception failure) {
                execution.startFailed(failure);
                return execution;
            } catch (Error fatal) {
                execution.startFailed(fatal);
                throw fatal;
            }
            execution.publishTask(task);
            return execution;
        } catch (Exception failure) {
            execution.startFailed(failure);
            return execution;
        } catch (Error fatal) {
            execution.startFailed(fatal);
            throw fatal;
        }
    }

    /**
     * Возвращает фактический Bulkhead для диагностики и управления состоянием.
     * Нельзя менять настройки во время выполнения команд; обработчики событий не должны блокировать
     * поток или бросать исключения.
     */
    public Bulkhead bulkhead() { return bulkhead; }

    /** Возвращает фактический CircuitBreaker; ограничения настройки и обработчиков те же, что у bulkhead(). */
    public CircuitBreaker circuitBreaker() { return circuitBreaker; }

    /** Возвращает фактический TimeLimiter; его обработчики должны быть неблокирующими и не бросать исключений. */
    public TimeLimiter timeLimiter() { return timeLimiter; }

    /**
     * Запрещает новые команды и запрашивает отмену активных с причиной SHUTDOWN.
     * Не ждёт очистки внешних ресурсов: её окончание подтверждает terminated() каждого выполнения.
     */
    @Override
    public void close() {
        List<Execution> executions;
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            executions = List.copyOf(active);
        }
        try {
            for (Execution execution : executions) {
                execution.requestCancellation(CancellationReason.SHUTDOWN);
            }
        } finally {
            if (ownsScheduler) scheduler.shutdownNow();
        }
    }

    int scheduledTimeoutCount() { return scheduler.getQueue().size(); }

    private static Throwable unwrap(Throwable failure) {
        // Ограничиваем обход обёрток исключения, чтобы повреждённая цепочка cause не заняла поток навсегда.
        for (int depth = 0; depth < 16
                && (failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null && failure.getCause() != failure; depth++) {
            failure = failure.getCause();
        }
        return failure;
    }

    private final class Execution implements CommandExecution<T> {
        // Логический результат отделён от completion задачи: таймаут не изменяет подтверждение её очистки.
        private final CompletableFuture<T> signal = new CompletableFuture<>();
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final CompletableFuture<Void> terminated = new CompletableFuture<>();
        private final CompletionStage<T> resultView = result.minimalCompletionStage();
        private final CompletionStage<Void> terminatedView = terminated.minimalCompletionStage();
        private final CommandScope scope = new CommandScope(
                timeLimiter.getTimeLimiterConfig().getTimeoutDuration(), this::scopeCancelled);
        private final DeadlineTimer timer = new DeadlineTimer(scope);
        private CommandTask<T> task;
        private boolean cancelDelivered;
        private boolean startEntered;
        private boolean resourcesFinished;
        private boolean permit;

        @Override public CompletionStage<T> result() { return resultView; }
        @Override public CompletionStage<Void> terminated() { return terminatedView; }
        @Override public void cancel() { requestCancellation(CancellationReason.CALLER); }

        /** Фиксирует начало только при действующем бюджете и отсутствии уже выбранного результата. */
        synchronized boolean beginStart() {
            if (!scope.isActive() || signal.isDone() || result.isDone()) return false;
            startEntered = true;
            return true;
        }

        /** Завершает отказ до запуска; подтверждение очистки сразу готово, поскольку задача не создавалась. */
        void reject(Throwable failure) {
            result.completeExceptionally(failure);
            terminated.complete(null);
        }

        /** Публикует результат после учёта политиками Resilience4j и убирает ненужный таймер. */
        void publishResult(T value, Throwable failure) {
            timer.cancelTimeout();
            if (failure == null) result.complete(value);
            else result.completeExceptionally(unwrap(failure));
        }

        /**
         * Сохраняет объект CommandTask и подписывается на подтверждение очистки до передачи накопленной отмены.
         * Такой порядок учитывает и задачу, которая завершится синхронно при вызове cancel().
         */
        void publishTask(CommandTask<T> published) {
            synchronized (this) { task = published; }
            CompletionStage<T> completion;
            try {
                completion = Objects.requireNonNull(published.completion(), "CommandTask.completion() returned null");
            } catch (RuntimeException | Error brokenContract) {
                // Задача нарушила контракт и не предоставила сигнал очистки.
                // Возвращать слот без подтверждения нельзя: он остаётся занят этой задачей.
                signal.completeExceptionally(brokenContract);
                requestCancellation(CancellationReason.CALLER);
                throw brokenContract;
            }
            completion.whenComplete((value, failure) -> {
                try {
                    finishResources();
                } finally {
                    completeSignal(value, failure);
                    terminated.complete(null);
                }
            });
            deliverCancellation();
        }

        /**
         * Обрабатывает сбой старта: до возврата объекта CommandTask за уже созданные ресурсы отвечает само действие.
         * После получения объекта CommandTask преждевременный возврат слота запрещён даже при нарушенном контракте задачи.
         */
        void startFailed(Throwable failure) {
            boolean published;
            synchronized (this) { published = task != null; }
            // После получения объекта CommandTask только завершение задачи вправе подтверждать очистку и возвращать слот.
            if (published) {
                signal.completeExceptionally(failure);
                return;
            }
            try {
                finishResources();
            } finally {
                completeSignal(null, failure);
                terminated.complete(null);
            }
        }

        /** Принимает исход задачи, только если он ещё успевает в бюджет и отмена не выбрана раньше. */
        void completeSignal(T value, Throwable failure) {
            if (!scope.tryComplete()) return;
            if (failure == null) signal.complete(value);
            else signal.completeExceptionally(unwrap(failure));
        }

        void requestCancellation(CancellationReason reason) { scope.cancel(reason); }

        /**
         * Немедленно завершает логический результат отменой и передаёт запрос остановки задаче.
         * Слот здесь не освобождается: задача ещё должна подтвердить очистку.
         */
        void scopeCancelled(CancellationReason reason) {
            if (reason == CancellationReason.TIMEOUT) {
                signal.completeExceptionally(TimeLimiter.createdTimeoutExceptionWithName(timeLimiter.getName(), null));
            } else {
                signal.completeExceptionally(new CommandCancelledException("Command cancelled: " + reason));
            }
            deliverCancellation();
        }

        /**
         * Передаёт первую отмену ровно один раз после публикации объекта CommandTask.
         * Пользовательский cancel() вызывается вне монитора, поскольку может синхронно завершить задачу.
         */
        void deliverCancellation() {
            CommandTask<T> target;
            CancellationReason reason;
            synchronized (this) {
                reason = scope.cancellationReason();
                if (task == null || reason == null || cancelDelivered || resourcesFinished) return;
                cancelDelivered = true;
                target = task;
            }
            try {
                target.cancel(reason);
            } catch (RuntimeException brokenContract) {
                // Исключение из cancel() не подтверждает очистку: слот удерживается до завершения задачи.
                LOG.log(System.Logger.Level.ERROR, "CommandTask.cancel violated its contract", brokenContract);
            }
        }

        /**
         * Один раз возвращает слот и удаляет выполнение из активных после подтверждённой очистки
         * либо отказа до старта. Поздние и повторные сигналы не возвращают слот второй раз.
         */
        void finishResources() {
            boolean release;
            synchronized (this) {
                if (resourcesFinished) return;
                resourcesFinished = true;
                release = permit;
                permit = false;
            }
            timer.cancelTimeout();
            try {
                if (release) {
                    // Отказ до старта возвращает слот без события CALL_FINISHED.
                    // Для начатой команды это событие совпадает с подтверждённой очисткой ресурсов.
                    if (startEntered) bulkhead.onComplete();
                    else bulkhead.releasePermission();
                }
            } catch (RuntimeException brokenListener) {
                // Resilience4j возвращает слот до уведомления обработчиков onCallFinished.
                LOG.log(System.Logger.Level.ERROR, "Bulkhead event listener violated its contract", brokenListener);
            } finally {
                active.remove(this);
                if (!startEntered) terminated.complete(null);
            }
        }
    }

    /**
     * Таймер будит проверку срока; решение о допустимости запуска и результата принимает общий CommandScope.
     * Поэтому задержка потока таймера не позволяет принять результат после истечения бюджета.
     */
    private final class DeadlineTimer {
        private final CommandScope scope;
        private ScheduledFuture<?> timeout;
        private boolean cancelled;

        DeadlineTimer(CommandScope scope) { this.scope = scope; }

        /** Запоминает отмену и удаляет таймер из очереди, даже если ссылка на него будет опубликована позже. */
        synchronized void cancelTimeout() {
            cancelled = true;
            if (timeout != null) timeout.cancel(false);
        }

        /** Ставит пробуждение на остаток общего бюджета, не начиная отсчёт срока заново. */
        void arm() {
            ScheduledFuture<?> future = scheduler.schedule(() -> { scope.isActive(); },
                    Math.max(0, scope.remainingNanos()), TimeUnit.NANOSECONDS);
            synchronized (this) {
                if (cancelled) future.cancel(false);
                else timeout = future;
            }
        }
    }
}
