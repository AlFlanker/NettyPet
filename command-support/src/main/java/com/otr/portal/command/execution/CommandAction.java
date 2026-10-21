package com.otr.portal.command.execution;

/** Операция без зависимости от транспорта. start() должен быть коротким и неблокирующим. */
@FunctionalInterface
public interface CommandAction<T> {
    /** При исключении до возврата задачи команда сама освобождает уже созданные ресурсы. */
    CommandTask<T> start() throws Exception;
}
