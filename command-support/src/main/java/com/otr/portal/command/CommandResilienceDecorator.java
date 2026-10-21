package com.otr.portal.command;

import com.otr.portal.command.execution.CommandAction;
import com.otr.portal.command.execution.CommandExecution;
import com.otr.portal.command.execution.CommandExecutor;
import com.otr.portal.command.execution.CommandNotExecutedException;
import com.otr.portal.command.internal.MonoCommandTask;
import com.otr.portal.command.internal.RetryTask;
import com.otr.portal.command.model.CommandContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Executes cold commands with one admission permit and deadline across all attempts.
 * The result may time out before resource cleanup; permits and registry leases remain
 * held until the task's completion confirms that cleanup really finished.
 */
@Component
public final class CommandResilienceDecorator implements AutoCloseable {
    private final CommandConfigRegistry commandConfigRegistry;
    private final ScheduledThreadPoolExecutor scheduler = CommandExecutor.newScheduler("command-timeout");
    // Only retry attempts use workers; the first Servlet handoff stays on its caller thread.
    private final ThreadPoolExecutor retryExecutor = newRetryExecutor();
    private final Set<CommandExecutor<?>> active = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private boolean closed;

    public CommandResilienceDecorator(CommandConfigRegistry commandConfigRegistry) {
        this.commandConfigRegistry = Objects.requireNonNull(commandConfigRegistry, "commandConfigRegistry");
    }

    <T> Mono<T> decorate(Supplier<Mono<T>> action, CommandContext context) {
        Objects.requireNonNull(action, "action");
        return Mono.deferContextual(subscriberContext ->
                executeTask(context, () -> MonoCommandTask.start(action, subscriberContext)));
    }

    /** The action is invoked only on subscription and after admission. */
    public <T> Mono<T> executeTask(CommandContext context, CommandAction<T> action) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        return Mono.create(sink -> {
            var executionRef = new AtomicReference<CommandExecution<T>>();
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            sink.onCancel(() -> {
                cancelled.set(true);
                var execution = executionRef.get();
                if (execution != null) execution.cancel();
            });
            if (cancelled.get()) return;
            var execution = start(context, action, created -> {
                executionRef.set(created);
                if (cancelled.get()) created.cancel();
            });
            executionRef.set(execution);
            if (cancelled.get()) execution.cancel();
            execution.result().whenComplete((value, failure) -> {
                if (failure != null) sink.error(unwrap(failure));
                else if (value == null) sink.success();
                else sink.success(value);
            });
        });
    }

    /** Starts immediately; callers that need a cold publisher should use executeTask. */
    public <T> CommandExecution<T> start(CommandContext context, CommandAction<T> action) {
        return start(context, action, ignored -> { });
    }

    private <T> CommandExecution<T> start(CommandContext context, CommandAction<T> action,
                                          Consumer<CommandExecution<T>> onCreated) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        CommandConfigRegistry.Lease lease;
        CommandExecutor<T> executor;
        synchronized (lifecycle) {
            if (closed) return rejected(new CommandNotExecutedException("Command decorator is closed"));
            lease = commandConfigRegistry.acquire(context);
            try {
                var holder = lease.holder();
                executor = new CommandExecutor<>(holder.getBulkhead(), holder.getTimeLimiter(),
                        holder.getCircuitBreaker(), scheduler);
            } catch (RuntimeException | Error invalidPolicy) {
                lease.close();
                throw invalidPolicy;
            }
            active.add(executor);
        }
        var termination = new CompletableFuture<Void>();
        try {
            var holder = lease.holder();
            var execution = executor.executeScoped(
                    scope -> RetryTask.start(action, holder.getRetry(), scheduler, retryExecutor, scope), observed -> {
                observed.terminated().whenComplete((ignored, failure) -> {
                    try {
                        lease.close();
                    } finally {
                        active.remove(executor);
                        if (failure == null) termination.complete(null);
                        else termination.completeExceptionally(unwrap(failure));
                    }
                });
                onCreated.accept(observed);
            });
            return new CommandExecution<>() {
                @Override public CompletionStage<T> result() { return execution.result(); }
                @Override public CompletionStage<Void> terminated() { return termination.minimalCompletionStage(); }
                @Override public void cancel() { execution.cancel(); }
            };
        } catch (RuntimeException | Error failure) {
            // The observer owns lease release even when a fatal start error is rethrown.
            // If a task broke its cleanup contract, keep its lease just as its permit.
            executor.close();
            throw failure;
        }
    }

    /** Stops new subscriptions and requests shutdown of all current operations without waiting for I/O. */
    @Override public void close() {
        List<CommandExecutor<?>> executions;
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            executions = List.copyOf(active);
        }
        try {
            executions.forEach(CommandExecutor::close);
        } finally {
            scheduler.shutdownNow();
            retryExecutor.shutdownNow();
        }
    }

    private static ThreadPoolExecutor newRetryExecutor() {
        var ids = new AtomicInteger();
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256), runnable -> {
                    Thread thread = new Thread(runnable, "command-retry-" + ids.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static <T> CommandExecution<T> rejected(Throwable failure) {
        var result = CompletableFuture.<T>failedFuture(failure).minimalCompletionStage();
        var terminated = CompletableFuture.<Void>completedFuture(null).minimalCompletionStage();
        return new CommandExecution<>() {
            @Override public CompletionStage<T> result() { return result; }
            @Override public CompletionStage<Void> terminated() { return terminated; }
            @Override public void cancel() { }
        };
    }

    private static Throwable unwrap(Throwable failure) {
        for (int i = 0; i < 16 && (failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null && failure.getCause() != failure; i++) {
            failure = failure.getCause();
        }
        return failure;
    }
}
