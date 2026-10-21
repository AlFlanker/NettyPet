package dev.example.resilience;

import java.util.concurrent.CompletionStage;

/** Результат команды и отдельный сигнал завершения очистки. */
public interface CommandExecution<T> {
    /**
     * Результат может завершиться по deadline раньше освобождения ресурсов.
     * Возвращённый stage нельзя использовать для отмены: для этого служит cancel().
     * Подписчики выполняются на потоке завершения и также должны быть неблокирующими.
     */
    CompletionStage<T> result();

    /** Завершается после фактического completion задачи и возврата bulkhead-слота. */
    CompletionStage<Void> terminated();

    /** Запрашивает отмену; первый сигнал отмены определяет причину. */
    void cancel();
}
