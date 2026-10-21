package dev.example.proxy.api;

/**
 * Синхронный обработчик метаданных до сетевого обмена. Экземпляры должны быть
 * потокобезопасны; блокирующие HTTP-вызовы, обращения к БД и чтение тела запрещены.
 * proceed() вызывается ровно один раз на том же потоке; для прикладного отказа
 * процессор выбрасывает ProxyRejection вместо продолжения цепочки.
 * Не сохраняйте request/chain после вызова: транспорт начнёт использовать их результаты.
 * Отказ любого участника окончателен, даже если внешний участник перехватил исключение.
 */
@FunctionalInterface
public interface RequestProcessor {
    /** Проверяет или меняет план и продолжает цепочку либо отклоняет запрос через ProxyRejection. */
    void process(IncomingRequest incoming, ProxyRequest outgoing, Chain chain) throws Exception;

    @FunctionalInterface
    interface Chain {
        /** Выполняет остаток цепочки синхронно; не отправляет запрос в сеть. */
        void proceed() throws Exception;
    }
}
