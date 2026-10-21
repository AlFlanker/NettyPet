package com.otr.portal.command.execution;

/** Локальная отмена; приложение может исключить её из статистики circuit breaker. */
public final class CommandCancelledException extends RuntimeException {
    public CommandCancelledException(String message) { super(message); }
    public CommandCancelledException(String message, Throwable cause) { super(message, cause); }
}
