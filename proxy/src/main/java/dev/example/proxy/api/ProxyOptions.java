package dev.example.proxy.api;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Неизменяемые лимиты экземпляра proxy; не зависят от системы конфигурации приложения.
 * responseBufferBytes ограничивает payload очереди, а не весь heap/direct memory:
 * Netty allocator, TLS, HTTP metadata и socket buffers учитываются отдельно.
 */
public record ProxyOptions(
        URI upstream,
        int ioThreads,
        int maxConcurrent,
        int connectionsPerOriginPerLoop,
        int pendingAcquiresPerPool,
        int chunkBytes,
        int responseBufferBytes,
        Duration connectTimeout,
        Duration acquireTimeout,
        Duration responseHeadersTimeout,
        Duration exchangeTimeout,
        Duration idleConnectionTimeout,
        List<URI> allowedOrigins) {
    public ProxyOptions {
        if (ioThreads < 1 || maxConcurrent < 1 || connectionsPerOriginPerLoop < 1 || pendingAcquiresPerPool < 1)
            throw new IllegalArgumentException("Concurrency settings must be positive");
        if (chunkBytes < 1024 || chunkBytes > 65536 || responseBufferBytes < chunkBytes * 4)
            throw new IllegalArgumentException("chunk-bytes: 1024..65536; response-buffer-bytes >= 4 chunks");
        for (Duration d : new Duration[] { connectTimeout, acquireTimeout, responseHeadersTimeout,
                exchangeTimeout, idleConnectionTimeout })
            if (d == null || d.isNegative() || d.toMillis() < 1 || d.toMillis() > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Timeouts must be positive and <= Integer.MAX_VALUE ms");
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (allowedOrigins.size() > 32) throw new IllegalArgumentException("At most 32 extra origins");
    }

    /** Совместимость прежних вызовов: ожидание final headers после upload ограничено 10 секундами. */
    public ProxyOptions(URI upstream, int ioThreads, int maxConcurrent,
                        int connectionsPerOriginPerLoop, int pendingAcquiresPerPool,
                        int chunkBytes, int responseBufferBytes, Duration connectTimeout,
                        Duration acquireTimeout, Duration exchangeTimeout,
                        Duration idleConnectionTimeout, List<URI> allowedOrigins) {
        this(upstream, ioThreads, maxConcurrent, connectionsPerOriginPerLoop, pendingAcquiresPerPool,
                chunkBytes, responseBufferBytes, connectTimeout, acquireTimeout, Duration.ofSeconds(10),
                exchangeTimeout, idleConnectionTimeout, allowedOrigins);
    }
}
