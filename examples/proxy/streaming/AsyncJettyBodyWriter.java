package com.otr.portal.proxy.streaming;

import io.netty.buffer.ByteBuf;
import jakarta.servlet.WriteListener;
import org.eclipse.jetty.ee11.servlet.HttpOutput;
import org.eclipse.jetty.ee11.servlet.ServletContextResponse;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

/**
 * Неблокирующая запись direct-буферов Netty в {@link HttpOutput#write(ByteBuffer)} Jetty без копирования в heap.
 * <p>
 * Первая запись выполняется только из {@link #onWritePossible()}: Jetty вызывает его после выхода
 * исходного потока из цепочки фильтров, поэтому обёртки Spring Security успевают отработать.
 * Буфер удерживается, пока Jetty не подтвердит окончание записи через {@link HttpOutput#isReady()}
 * или колбэк {@link WriteListener}.
 * <p>
 * Контракт асинхронного {@code ServletOutputStream}: писать можно только после {@code isReady() == true};
 * если запись не завершилась сразу, {@code isReady()} вернёт {@code false}, а Jetty позже вызовет
 * {@link #onWritePossible()}. Колбэки Jetty приходят в его потоках и переводятся в {@code callbackExecutor}
 * (event loop канала бэкенда), поэтому состояние writer'а не требует синхронизации.
 */
final class AsyncJettyBodyWriter implements ServletBodyWriter, WriteListener {
    private static final ByteBuffer[] NO_PARTS = new ByteBuffer[0];

    private final ServletContextResponse baseResponse;
    private final HttpOutput output;
    private final BodyWriteListener listener;
    private final Executor callbackExecutor;
    // Переиспользуются между чанками, чтобы не аллоцировать на каждую запись.
    private final Runnable resumeTask = this::resumeWriting;
    private final ByteBuffer[] singlePart = new ByteBuffer[1];

    // Буфер Netty, который сейчас пишется клиенту, и его NIO-представления (у composite их несколько).
    private ByteBuf pending;
    private ByteBuffer[] parts = NO_PARTS;
    private int nextPart;
    private boolean listenerRegistered;
    // Jetty уже вызвал onWritePossible: до этого писать нельзя.
    private boolean writePossible;
    // Jetty ещё читает память pending: освобождать буфер нельзя.
    private boolean writeInFlight;
    private boolean completeRequested;
    private boolean terminated;

    AsyncJettyBodyWriter(ServletContextResponse baseResponse, BodyWriteListener listener, Executor callbackExecutor) {
        this.baseResponse = baseResponse;
        this.output = baseResponse.getHttpOutput();
        this.listener = listener;
        this.callbackExecutor = callbackExecutor;
    }

    // [Шаг 7.2]
    @Override
    public void write(ByteBuf content) {
        if (terminated) {
            content.release();
            return;
        }
        if (pending != null) {
            content.release();
            failWriting(new IllegalStateException("Предыдущий буфер ещё не записан"));
            return;
        }
        pending = content;
        parts = collectParts(content);
        nextPart = 0;
        // Первый буфер ждёт onWritePossible после регистрации listener'а, следующие пишутся сразу.
        if (listenerRegistered) {
            flushPending();
        } else {
            registerWriteListener();
        }
    }

    // [Шаг 8.2] Если последний буфер ещё пишется, тело завершит flushPending.
    @Override
    public void complete() {
        if (terminated) {
            return;
        }
        completeRequested = true;
        if (pending == null) {
            finishWriting();
        }
    }

    // [Ошибка 1]
    @Override
    public void abort(Throwable cause) {
        if (terminated) {
            return;
        }
        terminated = true;
        // Если Jetty ещё пишет буфер, он освободится в resumeWriting/onError, когда Jetty его отпустит.
        releasePendingIfIdle();
        if (isStarted()) {
            // Обрыв соединения с клиентом: иначе он получил бы обрезанное тело как полный ответ.
            baseResponse.getServletRequestState().abort(cause);
        }
    }

    @Override
    public boolean isStarted() {
        return listenerRegistered || baseResponse.isCommitted();
    }

    // [Шаг 7.3] Поток Jetty: клиент готов принимать, продолжение - в event loop(т.е на потоке Netty).
    @Override
    public void onWritePossible() {
        callbackExecutor.execute(resumeTask);
    }

    // [Ошибка 4] Поток Jetty: запись клиенту не удалась.
    @Override
    public void onError(Throwable failure) {
        callbackExecutor.execute(() -> {
            writeInFlight = false;
            failWriting(failure);
        });
    }

    /**
     * NIO-представления буфера без копирования. Composite (тело после перезаписи) даёт по одному на компонент.
     */
    private ByteBuffer[] collectParts(ByteBuf content) {
        if (content.nioBufferCount() != 1) {
            return content.nioBuffers();
        }
        singlePart[0] = content.nioBuffer();
        return singlePart;
    }

    // [Шаг 7.2] Только для первого буфера; Jetty ответит вызовом onWritePossible (шаг 7.3).
    /**
     * Регистрируем слушателя(этот же экземпляр этого же класса) для получения коллбэков Jetty:
     * {@link #onWritePossible} и {@link #onError}
     */
    private void registerWriteListener() {
        listenerRegistered = true;
        try {
            output.setWriteListener(this);
        } catch (RuntimeException e) {
            failWriting(e);
        }
    }

    // [Шаг 7.3] Event loop.
    private void resumeWriting() {
        writeInFlight = false;
        writePossible = true;
        if (terminated) {
            releasePendingIfIdle();
            return;
        }
        flushPending();
    }

    /**
     * [Шаг 7.3] Пишет оставшиеся части текущего буфера, пока Jetty принимает запись синхронно.
     * Как только {@code isReady()} вернул {@code false}, выходим и ждём {@link #onWritePossible()}.
     * Когда буфер записан целиком, он освобождается, и источник получает сигнал на следующий (шаг 7.4)
     * или тело завершается, если {@link #complete()} уже был вызван (шаг 8.2).
     */
    private void flushPending() {
        if (!writePossible || pending == null) {
            return;
        }
        try {
            while (nextPart < parts.length) {
                if (!output.isReady()) {
                    return;
                }
                ByteBuffer part = parts[nextPart++];
                if (part.hasRemaining()) {
                    writeInFlight = true;
                    output.write(part);
                }
            }
            // Последняя часть ещё пишется асинхронно: буфер освободим в onWritePossible.
            if (!output.isReady()) {
                return;
            }
            writeInFlight = false;
        } catch (Exception e) {
            writeInFlight = false;
            failWriting(e);
            return;
        }
        releasePendingIfIdle();
        if (completeRequested) {
            finishWriting();
        } else {
            listener.onWritable();
        }
    }

    // [Шаг 8.2]
    private void finishWriting() {
        terminated = true;
        listener.onCompleted();
    }

    // [Ошибка 4]
    private void failWriting(Throwable cause) {
        if (terminated) {
            releasePendingIfIdle();
            return;
        }
        terminated = true;
        releasePendingIfIdle();
        listener.onFailed(cause);
    }

    private void releasePendingIfIdle() {
        if (pending == null || writeInFlight) {
            return;
        }
        pending.release();
        pending = null;
        parts = NO_PARTS;
        singlePart[0] = null;
        nextPart = 0;
    }
}
