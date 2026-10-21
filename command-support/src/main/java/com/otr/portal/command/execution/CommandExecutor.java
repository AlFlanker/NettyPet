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
 * Общий для приложения исполнитель коротких неблокирующих команд.
 * Bulkhead ограничивает живые операции, включая очистку после timeout; circuit breaker
 * наблюдает результат TimeLimiter. Классификацию прикладных ошибок задаёт его конфигурация.
 * start(), cancel(), обработчики событий Resilience4j и подписчики stage не должны блокировать.
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

    /** Uses the registry's actual policies and a lifecycle-managed shared scheduler. */
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
     * Timeout запускается до start(); при отмене во время start() сигнал доставляется
     * ровно один раз после публикации задачи. Error из start() освобождает слот и пробрасывается.
     */
    public CommandExecution<T> execute(CommandAction<T> command) {
        return execute(command, ignored -> { });
    }

    /** Observer is installed before admission/start, including a fatal synchronous start failure. */
    public CommandExecution<T> execute(CommandAction<T> command, Consumer<CommandExecution<T>> observer) {
        Objects.requireNonNull(command, "command");
        return executeScoped(ignored -> command.start(), observer);
    }

    public CommandExecution<T> executeScoped(ScopedCommandAction<T> command) {
        return executeScoped(command, ignored -> { });
    }

    /** The scope is established before admission and made visible before the first action starts. */
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
            // Обработчик события bulkhead может синхронно закрыть executor.
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
                    // close() may have selected local cancellation before scheduling failed.
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
     * Диагностика и управление состоянием R4j. Конфигурацию нельзя менять при работающем executor.
     * Установленные приложением обработчики событий должны быть неблокирующими и не бросать ошибок.
     */
    public Bulkhead bulkhead() { return bulkhead; }

    /** Контракт обработчиков событий и изменения конфигурации совпадает с bulkhead(). */
    public CircuitBreaker circuitBreaker() { return circuitBreaker; }

    /** Контракт обработчиков событий совпадает с bulkhead(). */
    public TimeLimiter timeLimiter() { return timeLimiter; }

    /**
     * Запрещает новые команды и запрашивает SHUTDOWN у активных. Не ждёт I/O-очистки;
     * её окончание наблюдается через terminated() каждого выполнения.
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
        // Ограничение защищает поток завершения даже от циклического графа cause.
        for (int depth = 0; depth < 16
                && (failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null && failure.getCause() != failure; depth++) {
            failure = failure.getCause();
        }
        return failure;
    }

    private final class Execution implements CommandExecution<T> {
        // Только копия completion задачи передаётся TimeLimiter: он изменяет этот future.
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

        synchronized boolean beginStart() {
            if (!scope.isActive() || signal.isDone() || result.isDone()) return false;
            startEntered = true;
            return true;
        }

        void reject(Throwable failure) {
            result.completeExceptionally(failure);
            terminated.complete(null);
        }

        void publishResult(T value, Throwable failure) {
            timer.cancelTimeout();
            if (failure == null) result.complete(value);
            else result.completeExceptionally(unwrap(failure));
        }

        void publishTask(CommandTask<T> published) {
            synchronized (this) { task = published; }
            CompletionStage<T> completion;
            try {
                completion = Objects.requireNonNull(published.completion(), "CommandTask.completion() returned null");
            } catch (RuntimeException | Error brokenContract) {
                // Нет сигнала фактической очистки: нельзя обещать освобождение ресурсов
                // и возвращать слот. Ограничение остаётся занятым сломанной задачей.
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

        void startFailed(Throwable failure) {
            boolean published;
            synchronized (this) { published = task != null; }
            // Задача уже опубликована: только её completion вправе вернуть слот.
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

        void completeSignal(T value, Throwable failure) {
            if (!scope.tryComplete()) return;
            if (failure == null) signal.complete(value);
            else signal.completeExceptionally(unwrap(failure));
        }

        void requestCancellation(CancellationReason reason) { scope.cancel(reason); }

        void scopeCancelled(CancellationReason reason) {
            if (reason == CancellationReason.TIMEOUT) {
                signal.completeExceptionally(TimeLimiter.createdTimeoutExceptionWithName(timeLimiter.getName(), null));
            } else {
                signal.completeExceptionally(new CommandCancelledException("Command cancelled: " + reason));
            }
            deliverCancellation();
        }

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
                // Ошибка cancel не доказывает очистку. Слот остаётся у задачи до completion.
                LOG.log(System.Logger.Level.ERROR, "CommandTask.cancel violated its contract", brokenContract);
            }
        }

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
                    // Неиспользованный слот не создаёт CALL_FINISHED. У начатой команды
                    // событие возникает только вместе с фактическим освобождением ресурсов.
                    if (startEntered) bulkhead.onComplete();
                    else bulkhead.releasePermission();
                }
            } catch (RuntimeException brokenListener) {
                // R4j возвращает permit до вызова onCallFinished listeners.
                LOG.log(System.Logger.Level.ERROR, "Bulkhead event listener violated its contract", brokenListener);
            } finally {
                active.remove(this);
                if (!startEntered) terminated.complete(null);
            }
        }
    }

    /** The timer is only a wake-up; all execution/result gates use the same monotonic scope. */
    private final class DeadlineTimer {
        private final CommandScope scope;
        private ScheduledFuture<?> timeout;
        private boolean cancelled;

        DeadlineTimer(CommandScope scope) { this.scope = scope; }

        synchronized void cancelTimeout() {
            cancelled = true;
            if (timeout != null) timeout.cancel(false);
        }

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
