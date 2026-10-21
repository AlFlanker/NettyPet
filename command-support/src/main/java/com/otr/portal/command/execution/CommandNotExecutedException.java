package com.otr.portal.command.execution;

/** Команда не была исполнена по локальной причине, например после закрытия executor. */
public final class CommandNotExecutedException extends RuntimeException {
    public CommandNotExecutedException(String message) { super(message); }
    public CommandNotExecutedException(String message, Throwable cause) { super(message, cause); }
}
