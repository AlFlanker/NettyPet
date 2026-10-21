package com.otr.portal.command.exception;

/**
 * Помечает ошибку, при которой политика может повторить операцию.
 * Само исключение не включает повторы: команда также должна разрешать несколько попыток
 * и явно подтверждать повторяемость своих данных и побочных эффектов.
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
