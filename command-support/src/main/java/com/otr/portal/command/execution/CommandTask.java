package com.otr.portal.command.execution;

import java.util.concurrent.CompletionStage;

/** Запущенная операция и кооперативная отмена её ресурсов. */
public interface CommandTask<T> {
    /**
     * Возвращает ненулевой CompletionStage, который завершается только после освобождения всех ресурсов.
     * Реализация не должна блокироваться или бросать исключения; повторные вызовы возвращают тот же объект.
     */
    CompletionStage<T> completion();

    /**
     * Запрашивает отмену потокобезопасно, без блокировки и исключений.
     * После запроса completion() обязан завершиться, когда очистка действительно закончится.
     * Если операция уже закончилась, сигнал ничего не делает.
     */
    void cancel(CancellationReason reason);
}
