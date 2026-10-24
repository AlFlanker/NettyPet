package dev.example.jettyclient.proxy;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.eclipse.jetty.ee11.servlet.ServletApiResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextResponse;
import org.eclipse.jetty.ee11.servlet.ServletCoreResponse;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/** Одна запись одновременно; callback возвращает право освободить исходный буфер. */
interface Downstream {
    void write(ByteBuffer buffer, Callback callback);
    void flush(Callback callback);
    default void cancel(Throwable failure) { }

    static Downstream create(HttpServletRequest request, HttpServletResponse response, int chunkBytes,
            Consumer<Runnable> dispatch, Consumer<Throwable> onError) throws IOException {
        // Не unwrap: response wrappers могут менять тело и выполнять onCommit callbacks.
        if (response.getClass() == ServletApiResponse.class) {
            ServletContextResponse context = ServletContextResponse.getServletContextResponse(response);
            if (!context.isWritingOrStreaming()) {
                Response core = ServletCoreResponse.wrap(context.getRequest(), response, false);
                return new Downstream() {
                    @Override public void write(ByteBuffer buffer, Callback callback) {
                        if (context.isWritingOrStreaming()) {
                            callback.failed(new IllegalStateException("Servlet output selected during native I/O"));
                        } else {
                            core.write(false, buffer, callback);
                        }
                    }
                    @Override public void flush(Callback callback) { write(BufferUtil.EMPTY_BUFFER, callback); }
                };
            }
        }
        return new Portable(response.getOutputStream(), chunkBytes, dispatch, onError);
    }

    /**
     * Через обёртки используем собственный непулируемый массив. При отмене контейнер
     * может ещё читать его, но исходный Content.Chunk уже можно безопасно освободить.
     */
    final class Portable implements Downstream, WriteListener {
        private final ServletOutputStream output;
        private final int chunkBytes;
        private final Consumer<Runnable> dispatch;
        private final Consumer<Throwable> onError;
        // A Servlet wrapper may report an error reentrantly from isReady/write/flush,
        // or from another thread. Only this latch is changed on the callback thread.
        private final AtomicReference<Throwable> firstError = new AtomicReference<>();
        private ByteBuffer buffer;
        private byte[] scratch;
        private Callback callback;
        private boolean flushing;
        private boolean cancelled;

        Portable(ServletOutputStream output, int chunkBytes, Consumer<Runnable> dispatch,
                Consumer<Throwable> onError) {
            this.output = output;
            this.chunkBytes = chunkBytes;
            this.dispatch = dispatch;
            this.onError = onError;
            output.setWriteListener(this);
        }

        @Override public void write(ByteBuffer bytes, Callback completion) {
            if (callback != null) throw new WritePendingException();
            Throwable failure = firstError.get();
            if (failure != null) { completion.failed(failure); return; }
            if (cancelled) { completion.failed(new IOException("Downstream cancelled")); return; }
            buffer = bytes;
            callback = completion;
            pump();
        }

        @Override public void flush(Callback completion) { write(BufferUtil.EMPTY_BUFFER, completion); }

        private void pump() {
            if (callback == null || stopIfFailed()) return;
            try {
                if (!ready()) return;
                while (buffer.hasRemaining()) {
                    if (stopIfFailed()) return;
                    if (scratch == null) scratch = new byte[chunkBytes];
                    int length = Math.min(buffer.remaining(), scratch.length);
                    buffer.get(scratch, 0, length);
                    if (stopIfFailed()) return;
                    output.write(scratch, 0, length);
                    if (stopIfFailed()) return;
                    // Even write(byte[]) may keep the array until readiness resumes.
                    if (!ready()) return;
                }
                if (!flushing) {
                    if (stopIfFailed()) return;
                    flushing = true;
                    output.flush();
                    if (stopIfFailed()) return;
                    if (!ready()) return;
                }
                if (stopIfFailed()) return;
                complete(null);
            } catch (Throwable failure) {
                cancel(failure);
            }
        }

        private boolean ready() {
            if (stopIfFailed()) return false;
            boolean ready = output.isReady();
            return !stopIfFailed() && ready;
        }

        /** Runs on the exchange owner, so callbacks and memory cleanup stay serialized. */
        private boolean stopIfFailed() {
            Throwable failure = firstError.get();
            if (failure == null) return cancelled;
            cancel(failure);
            return true;
        }

        private void complete(Throwable failure) {
            if (failure == null) failure = firstError.get();
            Callback completion = callback;
            callback = null;
            buffer = null;
            flushing = false;
            if (completion != null) {
                if (failure == null) completion.succeeded();
                else completion.failed(failure);
            }
        }

        @Override public void cancel(Throwable failure) {
            firstError.compareAndSet(null, failure == null ? new IOException("Downstream cancelled") : failure);
            cancelled = true;
            // Never return scratch to a pool or use it again after cancellation.
            scratch = null;
            complete(firstError.get());
        }

        @Override public void onWritePossible() { dispatch.accept(this::pump); }
        @Override public void onError(Throwable failure) {
            // Latch before enqueue: a reentrant error must invalidate an already
            // computed readiness result before the current pump performs more I/O.
            Throwable cause = failure == null ? new IOException("Servlet output failed") : failure;
            if (!firstError.compareAndSet(null, cause)) return;
            dispatch.accept(() -> {
                Throwable first = firstError.get();
                cancel(first);
                onError.accept(first);
            });
        }
    }
}
