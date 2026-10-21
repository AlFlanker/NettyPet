package dev.example.proxy.api;

/**
 * Синхронный interceptor метаданных. Экземпляры должны быть потокобезопасны;
 * блокирующие HTTP/DB вызовы и чтение тела здесь запрещены контрактом.
 * proceed() вызывается ровно один раз на том же потоке, либо выбрасывается ProxyRejection.
 * Не сохраняйте request/chain после вызова: транспорт начнёт использовать их результаты.
 * Отказ любого участника окончателен, даже если внешний участник перехватил исключение.
 */
@FunctionalInterface
public interface RequestProcessor {
    void process(IncomingRequest incoming, ProxyRequest outgoing, Chain chain) throws Exception;

    @FunctionalInterface
    interface Chain {
        /** Выполняет остаток цепочки синхронно; не отправляет запрос в сеть. */
        void proceed() throws Exception;
    }
}
