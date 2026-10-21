package dev.example.proxy.transport;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Необязательный адаптер контейнера для записи исходного буфера без промежуточной копии.
 * Прокси допускает не более одной операции write/flush одновременно. Реализация
 * не должна блокировать поток; completion можно вызвать сразу или из другого потока.
 * После вызова completion с успехом либо ошибкой контейнер не использует переданный буфер.
 * Этот сигнал разрешает освободить память, но не подтверждает получение данных клиентом по TCP.
 */
public interface DownstreamWriter {
    /**
     * Передаёт буфер контейнеру до единственного вызова completion. Контейнер может менять
     * position/limit, но не должен сохранять или читать буфер после completion. Если метод
     * бросает исключение до вызова completion, контейнер не сохраняет буфер и не вызывает
     * обработчик завершения позднее.
     */
    void write(ByteBuffer buffer, Completion completion) throws IOException;

    /** Отправляет накопленные данные и заголовки без окончания HTTP-ответа; результат сообщает единственным вызовом completion. */
    void flush(Completion completion) throws IOException;

    @FunctionalInterface
    interface Completion {
        /** Подтверждает завершение операции; null означает успех, иначе передаётся ошибка записи клиенту. */
        void complete(Throwable failure);
    }
}
