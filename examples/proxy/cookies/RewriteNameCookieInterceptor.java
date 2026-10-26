package com.otr.portal.proxy.cookies;

import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Stream;

/**
 * @author pobedenniy.alexey
 * @since 20.03.19
 */
public class RewriteNameCookieInterceptor implements CookieInterceptor {
    private static final Logger logCookiesRaw = LoggerFactory.getLogger("HttpProxy.cookies.raw");

    @Override
    public Stream<? extends Cookie> cookiesForBackend(Stream<? extends Cookie> cookieStream, HttpServletRequest req, Backend backend) {
        Stream<ProxiedCookie> proxiedCookieStream = cookieStream.map(ProxiedCookie::parseProxied);
        if (backend.isUseUris()) {
            return proxiedCookieStream
                    .filter(cookie -> cookieForBackend(backend, cookie))
                    .filter(cookie -> {
                        final boolean shouldSend = cookie.cookieForPath(req.getRequestURI(), backend.getUri());
                        logCookiesRaw.trace((shouldSend ? "Sending " : "Not sending ")  + "cookie {} to {} for URI {}", cookie.name(), backend.name(), req.getRequestURI());
                        return shouldSend;
                    })
                    .sorted();
        }
        return proxiedCookieStream;
    }

    @Override
    public Stream<String> cookieFromBackend(HttpServletRequest req, HttpServletResponse resp, Stream<String> cookies, Backend backend) {
        return cookies
                .map(e -> processSetCookieHeader(e, backend));
    }

    public Stream<Cookie> cookieFromBackend(Stream<Cookie> cookies, Backend backend) {
        return cookies
                .peek(c -> logCookiesRaw.trace("Proxying cookie {} from backend {}", c.toString(), backend.name()))
                .map(e -> toProxiedCookie(backend, e))
                .peek(c -> logCookiesRaw.trace("Proxied cookie to {} from backend {}", c.toString(), backend.name()));
    }


    private String processSetCookieHeader(String entry, Backend backend) {
        Cookie cookie = Cookie.parse(entry);
        logCookiesRaw.trace("Sending session cookie {} from backend {}", cookie.name(), backend.name());

        if (cookie.name().equals("IsElkMode")) {
            return new Cookie(cookie.name(), "true", cookie.expiresAt(), cookie.domain(), cookie.path(), cookie.secure(), cookie.httpOnly(), cookie.hostOnly(), cookie.persistent()).toString();
        }
        return toProxiedCookie(backend, cookie).toString();
    }

    private Cookie toProxiedCookie(Backend backend, Cookie cookie) {
        return ProxiedCookie.proxy(cookie, backend.getName(), backend.isUseUris() ? backend.getUri() : null);
    }

    private boolean cookieForBackend(Backend backend, ProxiedCookie cookie) {
        boolean forBackend = backend.getUri().equals(cookie.backend());
        boolean shouldSend = forBackend || backend.isSharedCookie(cookie.name());
        if (!shouldSend) {
            logCookiesRaw.trace("Not sending cookie {} as it is not for backend {}", cookie.name(), backend.name());
        } else {
            logCookiesRaw.trace("Sending cookie {} for backend {}", cookie.name(), backend.name());
        }
        return shouldSend;
    }
}
