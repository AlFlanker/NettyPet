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
 * <p>ServletCoreResponse сохраняет статус, заголовки и счётчик HttpOutput и передаёт
 * исходный ByteBuffer в асинхронный Response.write Jetty. Его обработчик завершения,
 * включая ошибку или отмену записи, возвращает память в распоряжение прокси. Сам возврат
 * из write(), запрос прерывания и AsyncListener.onComplete не подтверждают возврат буфера.</p>
 */
public final class JettyProxyIoAdapter implements ProxyIoAdapter {
    public static final JettyProxyIoAdapter INSTANCE = new JettyProxyIoAdapter();

    private JettyProxyIoAdapter() { }

    /** Разрешает чтение в прямой буфер только для известной реализации HttpInput Jetty. */
    @Override
    public boolean supportsDirectInput(ServletInputStream input) {
        // Стандартный read(ByteBuffer) в Servlet 6.1 выделяет byte[]. HttpInput Jetty
        // читает иначе; для обёрток и подклассов такое поведение не гарантируется.
        return input.getClass() == HttpInput.class;
    }

    /**
     * Создаёт неблокирующий канал записи для исходного ответа Jetty,
     * пока не выбраны ServletOutputStream или Writer.
     * Возвращает null для обёрток и уже выбранного вывода, сохраняя их переносимую семантику.
     */
    @Override
    public DownstreamWriter createWriter(HttpServletRequest request, HttpServletResponse response) {
        // Не извлекаем исходный response из обёрток: фильтры могут преобразовывать тело.
        // Для них используем переносимый путь через предоставленный ServletOutputStream.
        if (response.getClass() != ServletApiResponse.class) return null;
        ServletContextResponse contextResponse = ServletContextResponse.getServletContextResponse(response);
        if (contextResponse.isWritingOrStreaming()) return null;

        Response coreResponse = ServletCoreResponse.wrap(contextResponse.getRequest(), response, false);
        return new Writer(contextResponse, coreResponse);
    }

    /** Передаёт тот же ByteBuffer записи Jetty и подтверждает возврат владения через completion. */
    static final class Writer implements DownstreamWriter {
        private final ServletContextResponse servletResponse;
        private final Response coreResponse;

        Writer(ServletContextResponse servletResponse, Response coreResponse) {
            this.servletResponse = servletResponse;
            this.coreResponse = coreResponse;
        }

        // Response.write передаёт данные прямо в HttpStream, не в буфер Servlet HttpOutput.
        // Пустая запись после каждой порции тела не добавляет отправленных данных.
        @Override public boolean flushesOnWrite() { return true; }

        /** Передаёт текущую порцию без завершения ответа; completion подтверждает возврат буфера. */
        @Override
        public void write(ByteBuffer buffer, Completion completion) {
            Objects.requireNonNull(buffer);
            Objects.requireNonNull(completion);
            if (servletResponse.isWritingOrStreaming()) {
                // После передачи response прокси нельзя выбирать ServletOutputStream или Writer:
                // иначе ServletCoreResponse перейдёт к блокирующему копированию данных.
                completion.complete(new IllegalStateException("Servlet output was selected after native proxy I/O started"));
                return;
            }
            // last=false не завершает HTTP-ответ. Завершающий маркер отправит AsyncContext.complete(),
            // который прокси вызовет после подтверждения последней записи.
            coreResponse.write(false, buffer, new WriteCallback(completion));
        }

        /** Публикует накопленные заголовки и данные без завершающего HTTP-маркера. */
        @Override
        public void flush(Completion completion) {
            // Пустая запись отправляет заголовки без копирования тела и завершения HTTP-ответа.
            write(BufferUtil.EMPTY_BUFFER, completion);
        }
    }

    /** Не допускает повторного подтверждения возврата буфера при повторном уведомлении Jetty. */
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

        /** Сообщает Jetty, что уведомление не выполняет блокирующую работу. */
        @Override
        public InvocationType getInvocationType() {
            return InvocationType.NON_BLOCKING;
        }
    }
}
