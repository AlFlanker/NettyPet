package com.otr.portal.proxy.cookies;

import com.otr.portal.hosts.Backend;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Пробрасывает SSO-cookie из исходного запроса в запрос к бэкенду.
 *
 * @author alexraspopin
 * @since 07.05.2026
 */
@Service
@Order(Ordered.LOWEST_PRECEDENCE)
public class SsoCookieInterceptor implements CookieInterceptor {
    private final String authCookieName;

    public SsoCookieInterceptor(SsoCookieProperties ssoCookieProperties) {
        this.authCookieName = ssoCookieProperties.getAuthCookie();
    }

    @Override
    public Stream<? extends Cookie> cookiesForBackend(Stream<? extends Cookie> cookieStream, HttpServletRequest req, Backend backend) {
        Optional<Cookie> authCookie = authCookieFromRequest(req);
        if (authCookie.isEmpty()) {
            return cookieStream;
        }
        return Stream.concat(
                cookieStream
                        .filter(Objects::nonNull)
                        .filter(cookie -> !isAuthCookie(cookie)),
                Stream.of(authCookie.get())
        );
    }

    private Optional<Cookie> authCookieFromRequest(HttpServletRequest req) {
        String[] cookieParts = StringUtils.split(StringUtils.defaultString(req.getHeader(HttpHeaders.COOKIE)), ';');
        if (cookieParts == null) {
            return Optional.empty();
        }
        return Stream.of(cookieParts)
                .map(Cookie::parse)
                .filter(Objects::nonNull)
                .filter(this::isAuthCookie)
                .findFirst();
    }

    private boolean isAuthCookie(Cookie cookie) {
        return Objects.equals(authCookieName, cookie.name());
    }
}
