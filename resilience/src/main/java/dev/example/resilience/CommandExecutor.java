package dev.example.resilience;

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
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

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
        if (timeLimiterConfig.getTimeoutDuration().toMillis() < 1) {
            throw new IllegalArgumentException("TimeLimiter timeout must be at least one millisecond");
        }
        this.bulkhead = Bulkhead.of(name, bulkheadConfig);
        this.circuitBreaker = CircuitBreaker.of(name, circuitBreakerConfig);
        this.timeLimiter = TimeLimiter.of(name, timeLimiterConfig);
        this.scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "command-timeout-" + name);
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    /**
     * Возвращается после start() либо после отказа до запуска. Ожидания результата нет.
     * Timeout запускается до start(); при отмене во время start() сигнал доставляется
     * ровно один раз после публикации задачи. Error из start() освобождает слот и пробрасывается.
     */
    public CommandExecution<T> execute(Command<T> command) {
        Objects.requireNonNull(command, "command");
        Execution execution = new Execution();
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
                CompletionStage<T> stage;
                try {
                    stage = TimeLimiter.decorateCompletionStage(
                            timeLimiter, execution.timer, () -> execution.signal).get();
                } catch (RejectedExecutionException schedulingFailure) {
                    // close() мог завершить signal и остановить scheduler между проверкой
                    // выше и schedule(). Сохраняем локальную отмену для классификации CB.
                    if (!execution.signal.isDone()) {
                        execution.signal.completeExceptionally(new CommandNotExecutedException(
                                "Command timeout scheduler is unavailable", schedulingFailure));
                        execution.requestCancellation(CancellationReason.SHUTDOWN);
                    }
                    return execution.signal;
                }
                // В R4j 2.4 отмена timer отсутствует на exceptional completion.
                // Наш wrapper убирает timer из очереди при любом результате.
                stage.whenComplete((value, failure) -> {
                    execution.timer.cancelTimeout();
                    if (unwrap(failure) instanceof TimeoutException) {
                        execution.requestCancellation(CancellationReason.TIMEOUT);
                    }
                });
                return stage;
            };
            CircuitBreaker.decorateCompletionStage(circuitBreaker, limited).get()
                    .whenComplete(execution::publishResult);
            if (!execution.beginStart()) {
                execution.finishResources();
                return execution;
            }
            CommandTask<T> task;
            try {
                task = Objects.requireNonNull(command.start(), "Command.start() returned null");
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
            scheduler.shutdownNow();
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
        private final TimeoutScheduler timer = new TimeoutScheduler();
        private CommandTask<T> task;
        private CancellationReason cancellation;
        private boolean cancelDelivered;
        private boolean startEntered;
        private boolean resourcesFinished;
        private boolean permit;

        @Override public CompletionStage<T> result() { return resultView; }
        @Override public CompletionStage<Void> terminated() { return terminatedView; }
        @Override public void cancel() { requestCancellation(CancellationReason.CALLER); }

        synchronized boolean beginStart() {
            if (cancellation != null || signal.isDone() || result.isDone()) return false;
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
                    if (failure == null) signal.complete(value);
                    else signal.completeExceptionally(unwrap(failure));
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
                signal.completeExceptionally(failure);
                publishResult(null, failure);
                terminated.complete(null);
            }
        }

        void requestCancellation(CancellationReason reason) {
            synchronized (this) {
                if (resourcesFinished) return;
                if (cancellation == null) cancellation = reason;
            }
            // При TIMEOUT future уже завершён самим TimeLimiter с TimeoutException.
            if (reason != CancellationReason.TIMEOUT) {
                signal.completeExceptionally(new CommandCancelledException("Command cancelled: " + cancellation));
            }
            deliverCancellation();
        }

        void deliverCancellation() {
            CommandTask<T> target;
            CancellationReason reason;
            synchronized (this) {
                if (task == null || cancellation == null || cancelDelivered || resourcesFinished) return;
                cancelDelivered = true;
                target = task;
                reason = cancellation;
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

    /** Отдельный захват timer для выполнения, но один общий scheduler без очереди workers. */
    private final class TimeoutScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        private ScheduledFuture<?> timeout;
        private boolean cancelled;

        synchronized void cancelTimeout() {
            cancelled = true;
            if (timeout != null) timeout.cancel(false);
        }

        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ScheduledFuture<?> future = scheduler.schedule(command, delay, unit);
            synchronized (this) {
                if (cancelled) future.cancel(false);
                else timeout = future;
            }
            return future;
        }

        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("Only the TimeLimiter Runnable timer is supported");
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
        @Override public void shutdown() { throw new UnsupportedOperationException(); }
        @Override public List<Runnable> shutdownNow() { throw new UnsupportedOperationException(); }
        @Override public boolean isShutdown() { return scheduler.isShutdown(); }
        @Override public boolean isTerminated() { return scheduler.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return scheduler.awaitTermination(timeout, unit);
        }
        @Override public void execute(Runnable command) { throw new UnsupportedOperationException(); }
    }
}
