package dev.example.proxy.transport;

import dev.example.proxy.api.ProxyResult;
import java.util.concurrent.CompletionStage;

/** Управление одним streaming-обменом без зависимости от конкретной resilience-библиотеки. */
public interface ProxyCall {
    /**
     * Завершается после полного response body либо отмены и освобождения ресурсов:
     * buffers, pending acquire, возврат/закрытие канала, admission и попытка complete/abort.
     * Это read-only view; отмена его CompletableFuture не отменяет сетевой обмен.
     * Continuations могут выполняться на I/O-потоке и должны оставаться неблокирующими.
     */
    CompletionStage<ProxyResult> completion();

    /**
     * Идемпотентно ставит отмену в owner loop, не ожидая I/O. status должен быть 400..599.
     * До commit отправляется заданный статус; после commit выполняется abort.
     * Поздняя отмена завершённого обмена ничего не меняет. Освобождение подтверждает completion().
     */
    void cancel(int status);
}
