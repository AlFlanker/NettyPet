package dev.example.app.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Параметры транспорта, считываемые из раздела proxy конфигурации Spring.
 *
 * @param upstream базовый URI целевого сервера
 * @param ioThreads число сетевых циклов Netty
 * @param maxConcurrent предел незавершённых обменов, включая освобождение их ресурсов
 * @param connectionsPerOriginPerLoop предел соединений для одного origin в одном сетевом цикле
 * @param pendingAcquiresPerPool предел запросов, ожидающих соединения в одном пуле
 * @param chunkBytes размер порции тела при передаче
 * @param responseBufferBytes предел объёма полезных данных в очереди ответа
 * @param connectTimeout срок установки соединения с целевым сервером
 * @param acquireTimeout срок ожидания свободного соединения в пуле
 * @param responseHeadersTimeout срок ожидания финальных заголовков после полного отправления запроса
 * @param exchangeTimeout общий срок обмена; получение данных его не продлевает
 * @param idleConnectionTimeout срок хранения неиспользуемого соединения в пуле
 * @param allowedOrigins дополнительные разрешённые origin помимо origin базового URI
 */
@ConfigurationProperties("proxy")
public record ProxyProperties(
        @DefaultValue("http://localhost:9000") URI upstream,
        @DefaultValue("2") int ioThreads,
        @DefaultValue("256") int maxConcurrent,
        @DefaultValue("64") int connectionsPerOriginPerLoop,
        @DefaultValue("64") int pendingAcquiresPerPool,
        @DefaultValue("65536") int chunkBytes,
        @DefaultValue("262144") int responseBufferBytes,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration acquireTimeout,
        @DefaultValue("10s") Duration responseHeadersTimeout,
        @DefaultValue("60s") Duration exchangeTimeout,
        @DefaultValue("30s") Duration idleConnectionTimeout,
        @DefaultValue List<URI> allowedOrigins) {
    /** Создаёт независимые от Spring настройки и проверяет их ограничения в ProxyOptions. */
    public dev.example.proxy.api.ProxyOptions toOptions() {
        return new dev.example.proxy.api.ProxyOptions(upstream, ioThreads, maxConcurrent,
                connectionsPerOriginPerLoop, pendingAcquiresPerPool, chunkBytes, responseBufferBytes,
                connectTimeout, acquireTimeout, responseHeadersTimeout, exchangeTimeout,
                idleConnectionTimeout, allowedOrigins);
    }
}
