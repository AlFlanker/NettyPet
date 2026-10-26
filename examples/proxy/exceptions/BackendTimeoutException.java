package com.otr.portal.proxy.exceptions;

import com.otr.portal.hosts.Backend;

/**
 * @author pobedenniy.alexey
 * @since 14.08.18
 */
public class BackendTimeoutException extends BackendException {
    private final String targetUrl;

    public BackendTimeoutException(Backend backend, String targetUrl) {
        super(backend);
        this.targetUrl = targetUrl;
    }
}
