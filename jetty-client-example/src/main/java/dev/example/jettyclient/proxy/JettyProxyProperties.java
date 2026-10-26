package dev.example.jettyclient.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Настройки самостоятельного примера; лимиты destination не умножаются на число selectors. */
@ConfigurationProperties("example.proxy")
public record JettyProxyProperties(
        @DefaultValue("http://127.0.0.1:9000") URI upstream,
        @DefaultValue("65536") int chunkBytes,
        @DefaultValue("16384") int maxResponseHeadersBytes,
        @DefaultValue("128") int maxConcurrent,
        @DefaultValue("64") int maxConnectionsPerDestination,
        @DefaultValue("64") int maxQueuedRequestsPerDestination,
        @DefaultValue("2") int selectors,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("60s") Duration exchangeTimeout,
        @DefaultValue("30s") Duration idleTimeout,
        @DefaultValue("60s") Duration destinationIdleTimeout) {

    @ConstructorBinding
    public JettyProxyProperties {
        Objects.requireNonNull(upstream, "upstream");
        String scheme = upstream.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || upstream.isOpaque() || upstream.getHost() == null
                || upstream.getRawUserInfo() != null || upstream.getRawQuery() != null
                || upstream.getRawFragment() != null || upstream.getPort() == 0 || upstream.getPort() > 65535) {
            throw new IllegalArgumentException("upstream must be an HTTP(S) URI without user info, query or fragment");
        }
        if (chunkBytes < 1024 || chunkBytes > 65536) {
            throw new IllegalArgumentException("chunk-bytes must be 1024..65536");
        }
        if (maxResponseHeadersBytes < 1) {
            throw new IllegalArgumentException("max-response-headers-bytes must be positive");
        }
        if (maxConcurrent < 1 || maxConnectionsPerDestination < 1
                || maxQueuedRequestsPerDestination < 1 || selectors < 1) {
            throw new IllegalArgumentException("Concurrency, connection, queue and selector limits must be positive");
        }
        positiveMillis(connectTimeout, "connect-timeout");
        positiveMillis(exchangeTimeout, "exchange-timeout");
        try {
            exchangeTimeout.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("exchange-timeout is too large", overflow);
        }
        positiveMillis(idleTimeout, "idle-timeout");
        positiveMillis(destinationIdleTimeout, "destination-idle-timeout");
    }

    /** Сохраняет прежний API с положительным сроком удаления пустых destinations. */
    public JettyProxyProperties(URI upstream, int chunkBytes, int maxResponseHeadersBytes,
            int maxConcurrent, int maxConnectionsPerDestination, int maxQueuedRequestsPerDestination,
            int selectors, Duration connectTimeout, Duration exchangeTimeout, Duration idleTimeout) {
        this(upstream, chunkBytes, maxResponseHeadersBytes, maxConcurrent, maxConnectionsPerDestination,
                maxQueuedRequestsPerDestination, selectors, connectTimeout, exchangeTimeout, idleTimeout,
                Duration.ofSeconds(60));
    }

    private static void positiveMillis(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be at least 1ms");
        }
        try {
            if (value.toMillis() < 1) throw new IllegalArgumentException(name + " must be at least 1ms");
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " is too large", overflow);
        }
    }
}
