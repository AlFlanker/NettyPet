package com.otr.portal.proxy.streaming;

/**
 * Сигналы {@link ServletBodyWriter} источнику тела.
 * <p>
 * <b>Все методы вызываются на исполнителе {@code callbackExecutor} из {@link BodyWriterContext}.</b>
 */
public interface BodyWriteListener {

    /**
     * [Шаг 7.4] Предыдущий буфер записан, можно передавать следующий.
     */
    void onWritable();

    /**
     * [Шаг 8.2] Тело полностью записано после {@link ServletBodyWriter#complete()}.
     */
    void onCompleted();

    /**
     * [Ошибка 4] Запись в клиента не удалась (например, клиент закрыл соединение).
     */
    void onFailed(Throwable cause);
}
