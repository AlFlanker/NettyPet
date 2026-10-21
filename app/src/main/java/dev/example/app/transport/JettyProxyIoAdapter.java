package dev.example.app.transport;

import dev.example.proxy.transport.DownstreamWriter;
import dev.example.proxy.transport.ProxyIoAdapter;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.ee11.servlet.HttpInput;
import org.eclipse.jetty.ee11.servlet.ServletApiResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextResponse;
import org.eclipse.jetty.ee11.servlet.ServletCoreResponse;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * Оптимизация для Jetty 12.1 находится в приложении: переносимый proxy-модуль
 * знает только контракт владения буфером, но не классы контейнера.
 *
 * <p>ServletCoreResponse сохраняет Servlet status/headers и счётчик HttpOutput,
 * передавая исходный ByteBuffer в асинхронный Jetty Response.write. Его callback,
 * включая ошибку/отмену записи, возвращает владение памятью прокси. Возврат из write,
 * запрос abort и AsyncListener.onComplete сами по себе таким сигналом не являются.</p>
 */
public final class JettyProxyIoAdapter implements ProxyIoAdapter {
    public static final JettyProxyIoAdapter INSTANCE = new JettyProxyIoAdapter();

    private JettyProxyIoAdapter() { }

    @Override
    public boolean supportsDirectInput(ServletInputStream input) {
        // Servlet 6.1 default read(ByteBuffer) выделяет byte[]; Jetty HttpInput
        // переопределяет его. Обёртка/подкласс может иметь совсем другую семантику.
        return input.getClass() == HttpInput.class;
    }

    @Override
    public DownstreamWriter createWriter(HttpServletRequest request, HttpServletResponse response) {
        // Не распаковываем response: фильтры могут шифровать/сжимать/изменять тело.
        // Их ServletOutputStream должен участвовать в portable-пути.
        if (response.getClass() != ServletApiResponse.class) return null;
        ServletContextResponse contextResponse = ServletContextResponse.getServletContextResponse(response);
        if (contextResponse.isWritingOrStreaming()) return null;

        Response coreResponse = ServletCoreResponse.wrap(contextResponse.getRequest(), response, false);
        return new Writer(contextResponse, coreResponse);
    }

    static final class Writer implements DownstreamWriter {
        private final ServletContextResponse servletResponse;
        private final Response coreResponse;

        Writer(ServletContextResponse servletResponse, Response coreResponse) {
            this.servletResponse = servletResponse;
            this.coreResponse = coreResponse;
        }

        @Override
        public void write(ByteBuffer buffer, Completion completion) {
            Objects.requireNonNull(buffer);
            Objects.requireNonNull(completion);
            if (servletResponse.isWritingOrStreaming()) {
                // После передачи response прокси никто не должен выбирать stream/writer.
                // Иначе ServletCoreResponse незаметно перейдёт к блокирующей копии.
                completion.complete(new IllegalStateException("Servlet output was selected after native proxy I/O started"));
                return;
            }
            // last=false: HTTP end marker остаётся за обычным AsyncContext.complete(),
            // который proxy вызовет только после возврата последнего callback.
            coreResponse.write(false, buffer, new WriteCallback(completion));
        }

        @Override
        public void flush(Completion completion) {
            // Пустая запись публикует headers без копии тела и без end marker.
            write(BufferUtil.EMPTY_BUFFER, completion);
        }
    }

    /** Один объект на запись; CAS защищает владение и от повторного terminal callback. */
    private static final class WriteCallback extends AtomicBoolean implements Callback {
        private final DownstreamWriter.Completion completion;

        private WriteCallback(DownstreamWriter.Completion completion) {
            this.completion = completion;
        }

        @Override
        public void succeeded() {
            if (compareAndSet(false, true)) completion.complete(null);
        }

        @Override
        public void failed(Throwable failure) {
            if (compareAndSet(false, true)) completion.complete(failure);
        }

        @Override
        public InvocationType getInvocationType() {
            return InvocationType.NON_BLOCKING;
        }
    }
}
