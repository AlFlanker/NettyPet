package com.otr.portal.command.execution;

/** A resource action whose attempts share the executor's absolute budget and cancellation scope. */
@FunctionalInterface
public interface ScopedCommandAction<T> {
    CommandTask<T> start(CommandScope scope) throws Exception;
}
