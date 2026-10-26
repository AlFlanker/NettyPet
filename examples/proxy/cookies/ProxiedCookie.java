package com.otr.portal.proxy.cookies;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static org.apache.commons.lang3.StringUtils.startsWith;

/**
 * @author pobedenniy.alexey
 * @since 26.11.17
 */
public class ProxiedCookie extends Cookie implements Comparable<ProxiedCookie> {
    public static final String COOKIE_PREFIX_NAME = "$";
    public static final String COOKIE_POSTFIX_NAME = "$$";
    public static final String PROXY_COOKIE_PREFIX_NAME = "PROXY";
    public static final String PATH_DELIMITER = "__";
    private String backend;


    private ProxiedCookie(String name, String value, long expiresAt, String domain, String path, boolean secure, boolean httpOnly, boolean hostOnly, boolean persistent, String backend) {
        super(name, value, expiresAt, domain, path, secure, httpOnly, true, persistent);
        this.backend = backend;
    }

    public static ProxiedCookie parseProxied(Cookie cookie) {
        final String name = StringUtils.substringBetween(cookie.name(), PROXY_COOKIE_PREFIX_NAME + COOKIE_PREFIX_NAME, COOKIE_POSTFIX_NAME);
        String backend = StringUtils.substringBetween(cookie.name(), name + COOKIE_POSTFIX_NAME, PATH_DELIMITER);
        String path = StringUtils.replace(StringUtils.substringAfter(cookie.name(), backend + PATH_DELIMITER), "#", "/");
        if (StringUtils.isBlank(path)) {
            path = "/";
        }
        return new ProxiedCookie(name, cookie.value(), cookie.expiresAt(), cookie.domain(), path, cookie.secure(), cookie.httpOnly(), cookie.hostOnly(), cookie.persistent(), backend);
    }

    public static ProxiedCookie proxy(Cookie cookie, String backend, @Nullable String backendUri) {
        return new ProxiedCookie(
                generateCookieName(cookie.name(), cookie.path(), backendUri),
                cookie.value(),
                cookie.expiresAt(),
                cookie.domain(),
                "/",
                cookie.secure(),
                cookie.httpOnly(),
                true,
                cookie.persistent(),
                backend);
    }

    static String generateCookieName(@NotNull String name, @Nullable String path, @Nullable String backendUri) {
        String cookieName = PROXY_COOKIE_PREFIX_NAME + COOKIE_PREFIX_NAME + name + COOKIE_POSTFIX_NAME;
        if (backendUri != null) {
            cookieName += backendUri + PATH_DELIMITER;
            if (StringUtils.isNotBlank(path)) {
                cookieName += StringUtils.replace(path, "/", "#");
            }
            if (!cookieName.endsWith("#")) {
                cookieName += "#";
            }
        }
        return cookieName;
    }

    public static boolean isProxiedCookie(Cookie cookie) {
        return startsWith(cookie.name(), PROXY_COOKIE_PREFIX_NAME + COOKIE_PREFIX_NAME);
    }

    public String backend() {
        return backend;
    }

    @Override
    public int compareTo(@NotNull ProxiedCookie o) {
        String otherPath = o.path();
        return otherPath.length() - this.path().length();
    }
}
