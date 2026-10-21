package com.otr.portal.command.internal;

import com.otr.portal.command.execution.CancellationReason;
import com.otr.portal.command.execution.CommandAction;
import com.otr.portal.command.execution.CommandCancelledException;
import com.otr.portal.command.execution.CommandNotExecutedException;
import com.otr.portal.command.execution.CommandTask;
import com.otr.portal.command.execution.CommandScope;
import io.github.resilience4j.retry.Retry;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Объединяет повторяемые попытки в одну ресурсную задачу.
 * Завершение попытки подтверждает её очистку, поэтому следующая начинается только после него.
 * Внешний исполнитель удерживает общий слот Bulkhead, учитывает один исход в CircuitBreaker
 * и ограничивает все попытки единым предельным сроком.
 */
public final class RetryTask<T> implements CommandTask<T> {
    private static final System.Logger LOG = System.getLogger(RetryTask.class.getName());
    private final CommandAction<T> action;
    private final Retry.AsyncContext<T> retry;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Executor retryExecutor;
    private final CommandScope scope;
    private final CompletableFuture<T> completion = new CompletableFuture<>();
    private final CompletionStage<T> completionView = completion.minimalCompletionStage();
    private CommandTask<T> current;
    private ScheduledFuture<?> delayed;
    private CancellationReason cancellation;
    private boolean starting;
    private boolean cancelDelivered;
    private boolean finished;

    private RetryTask(CommandAction<T> action, Retry retry, ScheduledThreadPoolExecutor scheduler,
                      Executor retryExecutor, CommandScope scope) {
        this.action = Objects.requireNonNull(action, "action");
        this.retry = Objects.requireNonNull(retry, "retry").asyncContext();
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.retryExecutor = Objects.requireNonNull(retryExecutor, "retryExecutor");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    /**
     * Создаёт общую задачу и запускает первую попытку на вызывающем потоке.
     * Это сохраняет синхронную передачу владения для операций вроде Servlet startAsync().
     */
    public static <T> CommandTask<T> start(CommandAction<T> action, Retry retry,
                                          ScheduledThreadPoolExecutor scheduler,
                                          Executor retryExecutor, CommandScope scope) {
        var task = new RetryTask<>(action, retry, scheduler, retryExecutor, scope);
        task.startAttempt();
        return task;
    }

    /**
     * Проверяет общий срок перед вызовом действия, в том числе после ожидания в очереди работников.
     * Флаг starting не даёт принять отмену во время получения объекта CommandTask за подтверждение очистки.
     */
    private void startAttempt() {
        if (!checkScope()) return;
        synchronized (this) {
            delayed = null;
            if (finished || cancellation != null) return;
            starting = true;
            cancelDelivered = false;
        }
        CommandTask<T> task;
        try {
            if (!checkScope()) {
                synchronized (this) { starting = false; }
                finish(null, cancelled(scope.cancellationReason()));
                return;
            }
            task = Objects.requireNonNull(action.start(), "CommandAction.start() returned null");
        } catch (Exception failure) {
            synchronized (this) { starting = false; }
            attemptCompleted(null, failure);
            return;
        } catch (Error fatal) {
            synchronized (this) { starting = false; }
            finish(null, fatal);
            throw fatal;
        }
        synchronized (this) {
            current = task;
            starting = false;
        }
        checkScope();
        try {
            Objects.requireNonNull(task.completion(), "CommandTask.completion() returned null")
                    .whenComplete(this::attemptCompleted);
        } catch (RuntimeException | Error brokenContract) {
            // Подтверждения очистки нет: не завершаем общую задачу и не возвращаем её слот.
            // Пытаемся передать отмену; внешний срок всё равно ограничивает ожидание результата.
            LOG.log(System.Logger.Level.ERROR, "CommandTask completion contract violated", brokenContract);
            cancel(CancellationReason.CALLER);
            return;
        }
        deliverCancellation();
    }

    /**
     * Обрабатывает результат уже очищенной попытки и спрашивает Retry о следующем шаге.
     * Повтор планируется только после этого сигнала, поэтому ресурсы соседних попыток не перекрываются.
     */
    private void attemptCompleted(T value, Throwable failure) {
        CancellationReason reason;
        synchronized (this) {
            current = null;
            if (finished) return;
            reason = cancellation;
        }
        if (reason != null) {
            finish(null, cancelled(reason));
            return;
        }
        if (!checkScope()) return;
        long wait;
        try {
            wait = failure == null ? retry.onResult(value) : retry.onError(unwrap(failure));
            if (wait < 0 && failure == null) retry.onComplete();
        } catch (RuntimeException failureFromPolicy) {
            finish(null, failureFromPolicy);
            return;
        } catch (Error fatal) {
            finish(null, fatal);
            throw fatal;
        }
        if (wait < 0) {
            finish(value, failure == null ? null : unwrap(failure));
            return;
        }
        try {
            synchronized (this) {
                if (finished || cancellation != null) return;
                delayed = scheduler.schedule(this::dispatchAttempt, wait, TimeUnit.MILLISECONDS);
            }
        } catch (RejectedExecutionException unavailable) {
            finish(null, new CommandNotExecutedException("Retry scheduler is unavailable", unavailable));
        }
    }

    /**
     * Передаёт повтор в ограниченный рабочий пул, не выполняя действие команды на потоке таймера.
     * Переполненная или закрытая очередь даёт локальный отказ без нового обращения к внешней системе.
     */
    private void dispatchAttempt() {
        if (!checkScope()) return;
        try {
            retryExecutor.execute(this::startAttempt);
        } catch (RejectedExecutionException unavailable) {
            finish(null, new CommandNotExecutedException("Retry executor is unavailable or full", unavailable));
        }
    }

    /**
     * Проверяет общее состояние и переносит его отмену в задачу повторов.
     * Проверка нужна и до публикации этой обёртки внешнему исполнителю.
     */
    private boolean checkScope() {
        if (scope.isActive()) return true;
        var reason = scope.cancellationReason();
        if (reason != null) cancel(reason);
        return false;
    }

    @Override public CompletionStage<T> completion() { return completionView; }

    /**
     * Отменяет ожидание повтора либо запрашивает остановку текущей попытки.
     * Если объект CommandTask ещё создаётся, сохраняет отмену и передаёт её после публикации.
     * Метод не ждёт завершения запуска или очистки ресурсов.
     */
    @Override public void cancel(CancellationReason reason) {
        boolean idle;
        synchronized (this) {
            if (finished) return;
            if (cancellation == null) cancellation = reason;
            if (delayed != null) {
                delayed.cancel(false);
                delayed = null;
            }
            idle = current == null && !starting;
        }
        if (idle) finish(null, cancelled(cancellation));
        else deliverCancellation();
    }

    /** Передаёт сохранённую причину отмены текущей попытке один раз, не удерживая монитор во время обратного вызова. */
    private void deliverCancellation() {
        CommandTask<T> task;
        CancellationReason reason;
        synchronized (this) {
            if (finished || current == null || cancellation == null || cancelDelivered) return;
            cancelDelivered = true;
            task = current;
            reason = cancellation;
        }
        try {
            task.cancel(reason);
        } catch (RuntimeException brokenContract) {
            LOG.log(System.Logger.Level.ERROR, "CommandTask.cancel violated its contract", brokenContract);
        }
    }

    /** Один раз завершает общую задачу, когда активная попытка уже отсутствует или подтвердила очистку. */
    private void finish(T value, Throwable failure) {
        synchronized (this) {
            if (finished) return;
            finished = true;
        }
        if (failure == null) completion.complete(value);
        else completion.completeExceptionally(failure);
    }

    private static CommandCancelledException cancelled(CancellationReason reason) {
        return new CommandCancelledException("Command cancelled: " + reason);
    }

    private static Throwable unwrap(Throwable failure) {
        for (int depth = 0; depth < 16 && (failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null && failure.getCause() != failure; depth++) {
            failure = failure.getCause();
        }
        return failure;
    }
}
