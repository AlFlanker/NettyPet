package com.otr.portal.command.execution;

/** Сообщает об отказе запуска по локальной причине, например после закрытия исполнителя. */
public final class CommandNotExecutedException extends RuntimeException {
    public CommandNotExecutedException(String message) { super(message); }
    public CommandNotExecutedException(String message, Throwable cause) { super(message, cause); }
}
