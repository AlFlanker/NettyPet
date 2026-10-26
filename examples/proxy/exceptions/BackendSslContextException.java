package com.otr.portal.proxy.exceptions;

/**
 * Ошибки SSL контекста
 *
 * @author alexraspopin
 * @since 24.10.2025
 */
public class BackendSslContextException extends RuntimeException {
    public BackendSslContextException(String message) {
        super(message);
    }

    public BackendSslContextException(String message, Exception e) {
        super(message, e);
    }
}
