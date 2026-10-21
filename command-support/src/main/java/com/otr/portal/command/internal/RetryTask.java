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
 * One logical resource task across replayable attempts. An attempt's completion is
 * its cleanup acknowledgement, so the next attempt never overlaps previous cleanup.
 * The outer executor supplies the single admission permit, CB sample and deadline.
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

    public static <T> CommandTask<T> start(CommandAction<T> action, Retry retry,
                                          ScheduledThreadPoolExecutor scheduler,
                                          Executor retryExecutor, CommandScope scope) {
        var task = new RetryTask<>(action, retry, scheduler, retryExecutor, scope);
        task.startAttempt();
        return task;
    }

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
            // No cleanup acknowledgement exists: do not release this logical task.
            // Cancellation remains best-effort and the outer deadline still reports failure.
            LOG.log(System.Logger.Level.ERROR, "CommandTask completion contract violated", brokenContract);
            cancel(CancellationReason.CALLER);
            return;
        }
        deliverCancellation();
    }

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

    private void dispatchAttempt() {
        if (!checkScope()) return;
        try {
            retryExecutor.execute(this::startAttempt);
        } catch (RejectedExecutionException unavailable) {
            finish(null, new CommandNotExecutedException("Retry executor is unavailable or full", unavailable));
        }
    }

    private boolean checkScope() {
        if (scope.isActive()) return true;
        var reason = scope.cancellationReason();
        if (reason != null) cancel(reason);
        return false;
    }

    @Override public CompletionStage<T> completion() { return completionView; }

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
