package dev.example.proxy.transport;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Опциональные возможности Servlet-контейнера. Выбираются приложением при композиции;
 * транспорт не зависит от Jetty и не определяет тип контейнера через рефлексию.
 * Адаптер потокобезопасен; созданный им объект записи используется только своим обменом.
 */
public interface ProxyIoAdapter {
    ProxyIoAdapter PORTABLE = new ProxyIoAdapter() { };

    /**
     * Возвращает true, если read(ByteBuffer) читает без промежуточного массива байтов.
     * Стандартная реализация Servlet 6.1 этого не гарантирует; в таком случае прокси
     * читает в массив, принадлежащий буферу ByteBuf в куче JVM.
     */
    default boolean supportsDirectInput(ServletInputStream input) { return false; }

    /**
     * Создаёт средство асинхронной записи контейнера или возвращает null для стандартного
     * пути через WriteListener. До проверки возможностей нельзя получать выходной поток:
     * средство записи контейнера и ServletOutputStream нельзя использовать совместно.
     */
    default DownstreamWriter createWriter(HttpServletRequest request, HttpServletResponse response)
            throws IOException { return null; }
}
