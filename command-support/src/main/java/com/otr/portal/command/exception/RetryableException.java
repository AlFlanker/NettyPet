package com.otr.portal.command.exception;

/**
 * Ошибки для которых возможно повторение попытки выполнения
 *
 * @author alexraspopin
 * @since 09.02.2026
 */
public class RetryableException extends RuntimeException {
    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
    public RetryableException(String message) {
        super(message);
    }
}
