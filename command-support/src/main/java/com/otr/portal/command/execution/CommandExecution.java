package com.otr.portal.command.execution;

import java.util.concurrent.CompletionStage;

/** Результат команды и отдельный сигнал завершения очистки. */
public interface CommandExecution<T> {
    /**
     * Результат может завершиться по общему предельному сроку раньше освобождения ресурсов.
     * Отмена future, полученного из этого CompletionStage, не отменяет операцию: используйте cancel().
     * Подписчики выполняются на потоке завершения и также должны быть неблокирующими.
     */
    CompletionStage<T> result();

    /** Подтверждает фактическое завершение задачи, очистку её ресурсов и возврат слота Bulkhead. */
    CompletionStage<Void> terminated();

    /** Запрашивает отмену; первый сигнал отмены определяет причину. */
    void cancel();
}
