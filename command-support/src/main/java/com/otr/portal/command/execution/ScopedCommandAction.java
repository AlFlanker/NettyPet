package com.otr.portal.command.execution;

/** Запускает ресурсную операцию с общими для всех попыток предельным сроком и состоянием отмены. */
@FunctionalInterface
public interface ScopedCommandAction<T> {
    /**
     * Коротко и без блокировки запускает операцию с переданным общим состоянием выполнения.
     * При ошибке до возврата объекта CommandTask реализация сама освобождает уже созданные ресурсы.
     */
    CommandTask<T> start(CommandScope scope) throws Exception;
}
