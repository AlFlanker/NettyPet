package com.otr.portal.proxy.streaming;

import io.netty.buffer.ByteBuf;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Блокирующая запись в {@link ServletOutputStream} для ответов, которые нельзя писать асинхронно:
 * {@code PrintToStringHttpServletResponse}, {@code ContentCachingResponse} и не-Jetty контейнеры.
 * <p>
 * Direct-буфер копируется порциями через переиспользуемый буфер Netty, без массива размером с тело.
 */
final class BlockingBodyWriter implements ServletBodyWriter {
    private final HttpServletResponse response;
    private final BodyWriteListener listener;

    private ServletOutputStream output;
    private boolean started;
    private boolean terminated;

    BlockingBodyWriter(HttpServletResponse response, BodyWriteListener listener) {
        this.response = response;
        this.listener = listener;
    }

    // [Шаг 7.2–7.4] Синхронно: запись и сразу onWritable. Ошибка записи - [Ошибка 4].
    @Override
    public void write(ByteBuf content) {
        try {
            if (terminated) {
                return;
            }
            started = true;
            // Запись блокирует event loop, пока поток вывода не примет данные. Это запасной путь:
            // основные его случаи - обёртки, которые пишут в память и потому не ждут клиента.
            content.getBytes(content.readerIndex(), outputStream(), content.readableBytes());
        } catch (IOException e) {
            terminated = true;
            listener.onFailed(e);
            return;
        } finally {
            content.release();
        }
        listener.onWritable();
    }

    // [Шаг 8.2]
    @Override
    public void complete() {
        if (terminated) {
            return;
        }
        terminated = true;
        try {
            if (output != null) {
                output.flush();
            }
        } catch (IOException e) {
            listener.onFailed(e);
            return;
        }
        listener.onCompleted();
    }

    @Override
    public void abort(Throwable cause) {
        terminated = true;
    }

    @Override
    public boolean isStarted() {
        return started || response.isCommitted();
    }

    private ServletOutputStream outputStream() throws IOException {
        if (output == null) {
            output = response.getOutputStream();
        }
        return output;
    }
}
