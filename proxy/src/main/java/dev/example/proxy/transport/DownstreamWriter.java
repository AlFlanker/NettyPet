package dev.example.proxy.transport;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Необязательный адаптер контейнера для записи исходного буфера без промежуточной копии.
 * Прокси допускает не более одной операции write/flush одновременно. Реализация должна
 * быть неблокирующей; completion разрешено вызывать синхронно или с другого потока.
 * После completion (успеха ИЛИ ошибки) контейнер больше не обращается к переданному буферу.
 * Это гарантия владения памятью, а не подтверждение TCP ACK от клиента.
 */
public interface DownstreamWriter {
    /**
     * Передаёт буфер контейнеру до единственного вызова completion. Менять position/limit
     * буфера можно; сохранять или читать его после completion нельзя. Если метод бросил
     * исключение до callback, он не должен сохранять буфер или вызывать callback позднее.
     */
    void write(ByteBuffer buffer, Completion completion) throws IOException;

    /** Публикует накопленный batch/headers без успешного HTTP end marker. */
    void flush(Completion completion) throws IOException;

    @FunctionalInterface
    interface Completion {
        /** null означает успех; ненулевое значение — ошибку downstream-записи. */
        void complete(Throwable failure);
    }
}
