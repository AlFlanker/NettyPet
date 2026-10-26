package com.otr.portal.proxy.headers;

import com.google.common.collect.Multimap;
import com.otr.portal.core.model.http.Header;
import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.stream.Stream;

/**
 * Обработчик хидеров проксируемых запросов
 * @author pobedenniy.alexey
 * @since 25.09.17
 */
public interface HeaderProcessor {
    /**
     * Собирает хидеры запроса клиента для передачи на бэкенды
     * @param req     проксируемый запрос
     * @param backend бэкенд, на которой отправляется запрос
     * @return коллекцилю хидеров для отправки на бэкенд
     */
    Stream<Header> collectHeaders(HttpServletRequest req, Backend backend);

    /**
     * Обрабатывает хидеры, полученные от бэкенда для передачи их клиенту
     * @param headers хидеры, полученные от бэкенда
     * @param req
     * @param resp    ответ, которые будет отправлен клиенту
     * @param backend бэкенд, от которого получены хидеры
     */
    void consumeHeaders(Multimap<String, String> headers, HttpServletRequest req, HttpServletResponse resp, Backend backend);
}
