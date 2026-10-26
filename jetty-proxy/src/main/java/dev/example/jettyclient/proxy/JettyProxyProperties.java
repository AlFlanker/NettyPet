package dev.example.jettyclient.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Настройки транспорта; лимиты destination не умножаются на число selectors. */
public record JettyProxyProperties(
        URI upstream,
        int chunkBytes,
        int maxResponseHeadersBytes,
        int maxConcurrent,
        int maxConnectionsPerDestination,
        int maxQueuedRequestsPerDestination,
        int selectors,
        Duration connectTimeout,
        Duration exchangeTimeout,
        Duration idleTimeout,
        Duration destinationIdleTimeout) {

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

    /** Значения по умолчанию без зависимости от Spring или его binding-механизма. */
    public static JettyProxyProperties defaults() {
        return new JettyProxyProperties(URI.create("http://127.0.0.1:9000"), 65536, 16384,
                128, 64, 64, 2, Duration.ofSeconds(3), Duration.ofSeconds(60),
                Duration.ofSeconds(30), Duration.ofSeconds(60));
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
