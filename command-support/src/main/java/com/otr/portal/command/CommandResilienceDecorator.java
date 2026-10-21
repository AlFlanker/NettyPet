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
 * Выполняет холодные команды: все попытки используют один слот Bulkhead и общий предельный срок.
 * Таймаут результата не подтверждает очистку ресурсов. Слот и ссылка на поколение политик
 * удерживаются до завершения задачи, которое подтверждает фактическое освобождение ресурсов.
 */
@Component
public final class CommandResilienceDecorator implements AutoCloseable {
    private final CommandConfigRegistry commandConfigRegistry;
    private final ScheduledThreadPoolExecutor scheduler = CommandExecutor.newScheduler("command-timeout");
    // Рабочие потоки запускают только повторы. Первая попытка остаётся на вызывающем потоке,
    // чтобы HTTP-адаптер успел передать запрос в Servlet async до возврата фильтра.
    private final ThreadPoolExecutor retryExecutor = newRetryExecutor();
    private final Set<CommandExecutor<?>> active = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private boolean closed;

    public CommandResilienceDecorator(CommandConfigRegistry commandConfigRegistry) {
        this.commandConfigRegistry = Objects.requireNonNull(commandConfigRegistry, "commandConfigRegistry");
    }

    /**
     * Адаптирует обычный Mono к ресурсному исполнителю и сохраняет Reactor Context каждой подписки.
     * Захваченный контекст также используется при повторных попытках в рабочих потоках.
     */
    <T> Mono<T> decorate(Supplier<Mono<T>> action, CommandContext context) {
        Objects.requireNonNull(action, "action");
        return Mono.deferContextual(subscriberContext ->
                executeTask(context, () -> MonoCommandTask.start(action, subscriberContext)));
    }

    /**
     * Возвращает холодный Mono: действие запускается после подписки и допуска лимитерами.
     * Отмена подписки передаётся операции; освобождение её ресурсов ожидается отдельно от результата.
     */
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

    /**
     * Немедленно запускает защищённую операцию и возвращает раздельные сигналы результата и очистки.
     * Для запуска по подписке используйте {@link #executeTask(CommandContext, CommandAction)}.
     */
    public <T> CommandExecution<T> start(CommandContext context, CommandAction<T> action) {
        return start(context, action, ignored -> { });
    }

    /**
     * Фиксирует поколение политик и регистрирует выполнение до начала пользовательского действия.
     * Наблюдатель снимает фиксацию только после terminated(), включая отказ и сбой запуска.
     */
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
            // Наблюдатель освобождает ссылку на политики даже при пробросе Error из запуска.
            // Если задача не подтвердила очистку, сохраняем и эту ссылку, и занятый слот.
            executor.close();
            throw failure;
        }
    }

    /**
     * Запрещает новые запуски, запрашивает отмену активных операций и останавливает планировщики.
     * Метод не ждёт сетевой очистки: её завершение по-прежнему подтверждает задача.
     */
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

    /**
     * Создаёт ограниченный пул для повторных запусков, чтобы действия команды не занимали поток таймера.
     * При переполнении очереди запуск отклоняется без дополнительного вызова внешней системы.
     */
    private static ThreadPoolExecutor newRetryExecutor() {
        var ids = new AtomicInteger();
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256), runnable -> {
                    Thread thread = new Thread(runnable, "command-retry-" + ids.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Представляет отказ без запуска: результат сразу завершён исключением, ресурсов для очистки нет. */
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
