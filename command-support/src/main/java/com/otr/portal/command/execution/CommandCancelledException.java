package com.otr.portal.command.execution;

/** Сообщает о локальной отмене; приложение может исключить её из статистики CircuitBreaker. */
public final class CommandCancelledException extends RuntimeException {
    public CommandCancelledException(String message) { super(message); }
    public CommandCancelledException(String message, Throwable cause) { super(message, cause); }
}
