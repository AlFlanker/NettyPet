package dev.example.proxy.api;

import java.util.List;
import java.util.Objects;

/**
 * Неизменяемая цепочка, общая для всех запросов. Состояние одного выполнения никогда
 * не хранится в экземплярах Processor: они могут вызываться разными servlet-потоками.
 */
public final class ProcessorPipeline {
    private final List<RequestProcessor> processors;

    public ProcessorPipeline(List<RequestProcessor> processors) {
        this.processors = List.copyOf(processors);
    }

    /**
     * Выполняет цепочку синхронно. Первый обычный отказ сохраняется, даже если внешний
     * процессор перехватил его; {@link Error} имеет приоритет над обычным исключением.
     * Из нескольких {@code Error} повторно выбрасывается первый экземпляр.
     */
    public void execute(IncomingRequest incoming, ProxyRequest outgoing) throws Exception {
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(outgoing, "outgoing");
        var execution = new Execution();
        try {
            invoke(0, incoming, outgoing, execution);
        } catch (Exception | Error failure) {
            execution.recordFailure(failure);
        }
        // Достижение конца цепочки ещё не разрешает отправку: процессор может отклонить
        // запрос ПОСЛЕ proceed(), а внешний interceptor — по ошибке проглотить исключение.
        execution.rethrowFailure();
        if (!execution.completed) {
            throw new IllegalStateException("Processor chain did not reach completion");
        }
    }

    private void invoke(int index, IncomingRequest incoming, ProxyRequest outgoing, Execution execution) throws Exception {
        if (index == processors.size()) {
            execution.completed = true;
            return;
        }
        final class Continuation implements RequestProcessor.Chain {
            private final Thread owner = Thread.currentThread();
            private boolean proceeded;
            private boolean active = true;

            @Override
            public void proceed() throws Exception {
                if (Thread.currentThread() != owner || !active || proceeded) {
                    var failure = new IllegalStateException("Processor chain must proceed once, synchronously");
                    execution.recordFailure(failure);
                    throw failure;
                }
                proceeded = true;
                invoke(index + 1, incoming, outgoing, execution);
            }
        }
        var next = new Continuation();
        try {
            processors.get(index).process(incoming, outgoing, next);
            if (!next.proceeded) {
                throw new IllegalStateException("Processor must call proceed() or throw ProxyRejection");
            }
        } catch (Exception | Error failure) {
            execution.recordFailure(failure);
            throw failure;
        } finally {
            next.active = false;
        }
    }

    private static final class Execution {
        boolean completed;
        private volatile Throwable failure;

        // Синхронизация нужна только на ошибочном пути: в том числе при запрещённом
        // вызове продолжения с другого потока. Первый Error нельзя маскировать
        // обычным отказом; среди ошибок одной категории сохраняется первая причина.
        synchronized void recordFailure(Throwable cause) {
            if (failure == null || (cause instanceof Error && !(failure instanceof Error))) {
                failure = cause;
            }
        }

        void rethrowFailure() throws Exception {
            Throwable cause = failure;
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
        }
    }
}
