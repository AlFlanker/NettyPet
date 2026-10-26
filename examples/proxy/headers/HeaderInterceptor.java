package com.otr.portal.proxy.headers;

import com.otr.portal.core.model.http.Header;
import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Обработчик хидеров при обращении к бэкендам.
 * Позволяет добавлять/изменять хидеры, которые идут
 * от клиента к бэкенду
 * @author pobedenniy.alexey
 * @since 04.12.17
 */
public interface HeaderInterceptor {
    /**
     * Вызывается при формировании запроса к бэкенду
     * @param req     исходный запрос клиента
     * @param backend бэкенда, на который происходит запрос
     * @return коллекцию хидеров, которая будет дописана к запросу на бэкенд
     */
    default Stream<Header> headersForBackend(HttpServletRequest req, Backend backend) {
        return Stream.empty();
    }

    /**
     * Вызывается при процессинге ответа бэкенда
     *
     * @param req
     * @param resp
     * @param headers коллекция хидеров, полученных в ответе бэкенда
     * @param backend бэкенд, сформировавший ответ
     * @return новая коллекция хидеров
     */
    default Stream<Map.Entry<String, String>> headersFromBackend(HttpServletRequest req, HttpServletResponse resp, Stream<Map.Entry<String, String>> headers, Backend backend) {
        return headers;
    }
}
