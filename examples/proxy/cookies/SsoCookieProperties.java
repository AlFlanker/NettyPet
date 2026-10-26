package com.otr.portal.proxy.cookies;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки SSO-cookie, которую нужно пробрасывать в запросы к бэкендам.
 *
 * @author alexraspopin
 * @since 07.05.2026
 */
@ConfigurationProperties(prefix = "admin.security.sso")
public class SsoCookieProperties {
    public static final String DEFAULT_AUTH_COOKIE = "SB_SSO_SESSION";

    private String authCookie = DEFAULT_AUTH_COOKIE;

    public String getAuthCookie() {
        return authCookie;
    }

    public void setAuthCookie(String authCookie) {
        this.authCookie = StringUtils.defaultIfBlank(authCookie, DEFAULT_AUTH_COOKIE);
    }
}
