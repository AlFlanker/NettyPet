package com.otr.portal.proxy.streaming;

import io.netty.buffer.ByteBuf;

/**
 * Запись тела проксируемого ответа в сервлетный ответ.
 * <p>
 * Методы вызываются из одного потока (event loop канала бэкенда).
 * В каждый момент в записи находится не более одного буфера:
 * следующий передаётся только после {@link BodyWriteListener#onWritable()}.
 */
public interface ServletBodyWriter {

    /**
     * Передаёт буфер на запись. Владение буфером переходит к writer'у.
     */
    void write(ByteBuf content);

    /**
     * Завершает тело после записи переданного буфера и сообщает {@link BodyWriteListener#onCompleted()}.
     */
    void complete();

    /**
     * Прерывает запись, освобождает незаписанный буфер. Если ответ уже начал уходить клиенту, соединение обрывается.
     */
    void abort(Throwable cause);

    /**
     * Началась ли запись тела: после этого ответ нельзя заменить сообщением об ошибке.
     */
    boolean isStarted();
}
