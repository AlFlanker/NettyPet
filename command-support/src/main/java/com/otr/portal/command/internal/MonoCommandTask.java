package com.otr.portal.command.internal;

import com.otr.portal.command.execution.CancellationReason;
import com.otr.portal.command.execution.CommandCancelledException;
import com.otr.portal.command.execution.CommandTask;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Адаптирует обычный Mono, у которого завершение или отмена подписки завершает владение ресурсами.
 * Для источника с отдельной асинхронной очисткой нужен явный CommandTask: отмена подписки
 * сама по себе не подтверждает освобождение таких внешних ресурсов.
 */
public final class MonoCommandTask<T> extends BaseSubscriber<T> implements CommandTask<T> {
    private final CompletableFuture<T> completion = new CompletableFuture<>();
    private final CompletionStage<T> completionView = completion.minimalCompletionStage();
    private final Context subscriberContext;
    private T value;
    private Throwable failure;

    private MonoCommandTask(ContextView subscriberContext) {
        this.subscriberContext = Context.of(Objects.requireNonNull(subscriberContext, "subscriberContext"));
    }

    /**
     * Подписывается на источник отдельным подписчиком и откладывает создание Mono до подписки.
     * Контекст передаётся явно, чтобы внутренняя подписка не теряла данные исходного подписчика.
     */
    public static <T> CommandTask<T> start(Supplier<Mono<T>> source, ContextView subscriberContext) {
        var task = new MonoCommandTask<T>(subscriberContext);
        Mono.defer(() -> Objects.requireNonNull(source.get(), "doExecute() returned null")).subscribe(task);
        return task;
    }

    /** Передаёт каждой попытке неизменяемый Reactor Context исходного подписчика этой команды. */
    @Override public Context currentContext() { return subscriberContext; }

    @Override protected void hookOnNext(T value) { this.value = value; }
    @Override protected void hookOnError(Throwable failure) { this.failure = failure; }
    /** Переводит завершение подписки в результат задачи; отмену отличает от успешного пустого Mono. */
    @Override protected void hookFinally(SignalType type) {
        if (type == SignalType.CANCEL && failure == null)
            failure = new CommandCancelledException("Reactive source cancelled");
        if (failure == null) completion.complete(value);
        else completion.completeExceptionally(failure);
    }
    @Override public CompletionStage<T> completion() { return completionView; }
    @Override public void cancel(CancellationReason reason) { cancel(); }
}
