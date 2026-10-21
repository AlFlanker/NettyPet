package dev.example.proxy.transport;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Опциональные возможности Servlet-контейнера. Выбираются приложением при композиции;
 * транспорт не зависит от Jetty и не угадывает поддерживаемые реализации по reflection.
 * Адаптер потокобезопасен, а возвращённый writer принадлежит одному обмену.
 */
public interface ProxyIoAdapter {
    ProxyIoAdapter PORTABLE = new ProxyIoAdapter() { };

    /**
     * true только если read(ByteBuffer) входного потока имеет эффективную реализацию.
     * Default Servlet 6.1 метод может выделять промежуточный byte[], поэтому portable
     * вариант читает непосредственно в heap ByteBuf прежним способом.
     */
    default boolean supportsDirectInput(ServletInputStream input) { return false; }

    /**
     * null сохраняет portable WriteListener-путь. Метод не должен выбирать output stream
     * до проверки возможностей: native writer и ServletOutputStream нельзя смешивать.
     */
    default DownstreamWriter createWriter(HttpServletRequest request, HttpServletResponse response)
            throws IOException { return null; }
}
