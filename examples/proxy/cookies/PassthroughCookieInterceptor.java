package com.otr.portal.proxy.cookies;

import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.Host;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.stream.Stream;

import static java.util.function.Predicate.not;
import static java.util.stream.Stream.empty;
import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.split;
import static org.springframework.http.HttpHeaders.COOKIE;

/**
 * Пробрасывает специфичные cookies из настроек {@link Host#getPassthroughCookies()}.
 *
 * @author alexraspopin
 * @since 11.06.2026
 */
@Service
public class PassthroughCookieInterceptor implements CookieInterceptor {
    private static final Logger logCookiesRaw = LoggerFactory.getLogger("HttpProxy.cookies.raw");

    @Override
    public Stream<? extends Cookie> cookiesForBackend(
            Stream<? extends Cookie> cookieStream,
            HttpServletRequest req,
            Backend backend
    ) {
        if (backend.hasNoPassthroughCookies()) {
            return cookieStream;
        }
        return Stream.concat(
                cookieStream
                        .filter(Objects::nonNull)
                        .filter(not(backend::isPassthroughCookie)),
                passthroughCookiesFromRequest(req, backend)
        );
    }

    private Stream<Cookie> passthroughCookiesFromRequest(
            HttpServletRequest req,
            Backend backend
    ) {
        var header = req.getHeader(COOKIE);
        var defaultHeadersString = defaultString(header);
        String[] cookieParts = split(defaultHeadersString, ';');
        if (cookieParts == null) {
            return empty();
        }
        return Stream.of(cookieParts)
                .map(Cookie::parse)
                .filter(Objects::nonNull)
                .filter(backend::isPassthroughCookie)
                .peek(cookie ->
                        logCookiesRaw.trace("Отправка cookie {} в бэкенд {} без обработки Портала", cookie.name(), backend.name()));
    }
}
