package com.otr.portal.proxy.cookies;

import com.otr.portal.hosts.Backend;
import com.otr.portal.proxy.cookies.service.CookieStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.stream.Stream;

import static com.otr.portal.RequestAttributeNames.REQUEST_ATTRIBUTE_CLIENT_ID;


/**
 * @author pobedenniy.alexey
 * @since 20.03.19
 */
@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExternalStorageCookieInterceptor implements CookieInterceptor {
    private static final Logger logCookies = LoggerFactory.getLogger("HttpProxy.cookies");

    private final CookieStorageService cookieStorageService;
    private final RewriteNameCookieInterceptor rewriteNameCookieInterceptor;

    public ExternalStorageCookieInterceptor(CookieStorageService cookieStorageService) {
        this.cookieStorageService = cookieStorageService;
        this.rewriteNameCookieInterceptor = new RewriteNameCookieInterceptor();
    }

    @Override
    public Stream<? extends Cookie> cookiesForBackend(Stream<? extends Cookie> cookieStream, HttpServletRequest req, Backend backend) {
        if (backend.isStoreCookiesOnServer()) {
            final String clientId = (String) req.getAttribute(REQUEST_ATTRIBUTE_CLIENT_ID);
            final String requestURI = req.getRequestURI();
            final Stream<Cookie> loadedCookies = cookieStorageService
                    .loadUserCookiesForBackend(clientId, backend)
                    .filter(cookie -> !cookie.persistent() || cookie.isNotExpired())
                    .peek(cookie -> logCookies.trace("Для запроса {} клиента {} на бэкенд {} из хранилища загружена cookie {}", requestURI, clientId, backend.getName(), cookie.toString()))
                    .filter(cookie -> cookie.cookieForPath(requestURI, backend.getUri()));
            return Stream.concat(loadedCookies, rewriteNameCookieInterceptor.cookiesForBackend(cookieStream, req, backend));
        } else {
            return rewriteNameCookieInterceptor.cookiesForBackend(cookieStream, req, backend);
        }
    }

    @Override
    public Stream<String> cookieFromBackend(HttpServletRequest req, HttpServletResponse resp, Stream<String> cookies, Backend backend) {
        if (backend.isStoreCookiesOnServer()) {
            final String clientId = (String) req.getAttribute(REQUEST_ATTRIBUTE_CLIENT_ID);
            Flux<Cookie> allCookies = Flux
                    .fromStream(cookies)
                    .map(Cookie::parse)
                    .cache();

            final Stream<Cookie> nonHttpOnlyCookies = allCookies
                    .filter(c -> !c.httpOnly())
                    .toStream();
            final String requestURI = req.getRequestURI();
            final Stream<Cookie> cookiesToSave = allCookies
                    .filter(Cookie::httpOnly)
                    .toStream()
                    .peek(cookie -> logCookies.trace("Для запроса {} клиента {} на бэкенд {} сохраняется cookie {}. Эта cookie не будет отправлена клиенту", requestURI, clientId, backend.getName(), cookie));

            final Stream<Cookie> httpOnlyCookies = cookieStorageService.addUserCookies(clientId, cookiesToSave, backend);


            return Stream
                    .concat(rewriteNameCookieInterceptor.cookieFromBackend(nonHttpOnlyCookies, backend), httpOnlyCookies)
                    .peek(cookie -> logCookies.trace("Для запроса {} клиента {} на бэкенд {} в ответ добавляется cookie {}", requestURI, clientId, backend.getName(), cookie))
                    .map(Cookie::toString);
        } else {
            return rewriteNameCookieInterceptor.cookieFromBackend(req, resp, cookies, backend);
        }
    }
}
