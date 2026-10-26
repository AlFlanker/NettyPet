package com.otr.portal.proxy.headers;


import com.otr.portal.proxy.cookies.Cookie;

/**
 * Кука, удаляемая с машины клиента.
 * Чтобы удалить куку, необходимо передать хидер "Set-Cookie" вместе с ответом пользователю
 * @author gusev.aleksandr
 * @since 02.02.2018
 */
public class DeletedCookie extends Cookie {

    DeletedCookie(String name, String value, long expiresAt, String domain, String path, boolean secure, boolean httpOnly, boolean hostOnly, boolean persistent) {
        super(name, value, expiresAt, domain, path, secure, httpOnly, true, persistent);
    }

    public static <T extends Cookie> DeletedCookie of(T cookie) {
        return new DeletedCookie(cookie.name(), "", Long.MIN_VALUE, cookie.domain(), cookie.path(),
                cookie.secure(), cookie.httpOnly(), cookie.hostOnly(), true);
    }
}