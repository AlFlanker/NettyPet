package dev.example.jettyclient.proxy;

/**
 * Упорядоченное расширение заголовков без доступа к Servlet, телу или сетевому запросу.
 * Spring beans вызываются по {@code @Order}, по одному разу для запроса и финального ответа.
 * Экземпляр обслуживает разные обмены одновременно: он должен быть потокобезопасным.
 * Методы обязаны быстро завершаться без блокировок, чтения тела, сетевых вызовов и БД:
 * они выполняются на владельце обмена вместе с обработкой отмены и deadline.
 * Редактор нельзя сохранять или передавать другому потоку; после вызовов он закрывается.
 * Ошибка расширения означает локальный HTTP 500 до отправки заголовков клиенту.
 */
public interface ProxyHeaderCustomizer {
    /** Вызывается после удаления служебных заголовков, до отправки запроса upstream. */
    default void customizeRequest(ProxyRequestMetadata request, ProxyHeaderEditor headers) { }

    /** Вызывается для финального ответа, до установки и отправки его заголовков клиенту. */
    default void customizeResponse(ProxyRequestMetadata request, int status, ProxyHeaderEditor headers) { }
}
