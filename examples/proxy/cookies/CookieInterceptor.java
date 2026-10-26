package com.otr.portal.proxy.cookies;

import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.stream.Stream;

/**
 * Обработчик кук при обращении к бэкендам
 *
 * @author pobedenniy.alexey
 * @since 27.08.18
 */
public interface CookieInterceptor {
    /**
     * Вызывается при формировании запроса к бэкенду
     *
     * @param cookieStream сформированный и готовый для отправки бэкенду набор кук
     * @param req          исходный запрос
     * @param backend      бэкенд на который отправляется запрос
     * @return модифицированный набор кук
     */
   default Stream<? extends Cookie> cookiesForBackend(Stream<? extends Cookie> cookieStream, HttpServletRequest req, Backend backend){
       return cookieStream;
   }

    /**
     * Вызывается при парсинге ответа бэкенда
     *
     * @param req
     * @param resp
     * @param cookies все куки, выставляемые бэкендом
     * @param backend бэкенд, сформировавший ответ
     * @return новая коллекция кук
     */
    default Stream<String> cookieFromBackend(HttpServletRequest req, HttpServletResponse resp, Stream<String> cookies, Backend backend){
        return cookies;
    }
}
