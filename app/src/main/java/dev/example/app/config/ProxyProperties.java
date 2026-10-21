package dev.example.app.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("proxy")
public record ProxyProperties(
        @DefaultValue("http://localhost:9000") URI upstream,
        @DefaultValue("2") int ioThreads,
        @DefaultValue("256") int maxConcurrent,
        @DefaultValue("64") int connectionsPerOriginPerLoop,
        @DefaultValue("64") int pendingAcquiresPerPool,
        @DefaultValue("16384") int chunkBytes,
        @DefaultValue("262144") int responseBufferBytes,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration acquireTimeout,
        @DefaultValue("10s") Duration responseHeadersTimeout,
        @DefaultValue("60s") Duration exchangeTimeout,
        @DefaultValue("30s") Duration idleConnectionTimeout,
        @DefaultValue List<URI> allowedOrigins) {
    public dev.example.proxy.api.ProxyOptions toOptions() {
        return new dev.example.proxy.api.ProxyOptions(upstream, ioThreads, maxConcurrent,
                connectionsPerOriginPerLoop, pendingAcquiresPerPool, chunkBytes, responseBufferBytes,
                connectTimeout, acquireTimeout, responseHeadersTimeout, exchangeTimeout,
                idleConnectionTimeout, allowedOrigins);
    }
}
