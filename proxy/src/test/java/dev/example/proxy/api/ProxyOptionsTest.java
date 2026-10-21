package dev.example.proxy.api;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProxyOptionsTest {
    @Test
    void legacyConstructorKeepsASeparateTenSecondResponseHeadersTimeout() {
        ProxyOptions options = new ProxyOptions(URI.create("http://localhost:9000"), 1, 8, 4, 4,
                1024, 4096, Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofMinutes(1), Duration.ofSeconds(30), List.of());

        assertEquals(Duration.ofSeconds(10), options.responseHeadersTimeout());
        assertEquals(Duration.ofMinutes(1), options.exchangeTimeout());
    }

    @Test
    void responseHeadersTimeoutIsConfigurableAndValidatedIndependently() {
        assertEquals(Duration.ofMillis(750), options(Duration.ofMillis(750)).responseHeadersTimeout());
        for (Duration invalid : new Duration[] { null, Duration.ZERO, Duration.ofMillis(-1),
                Duration.ofNanos(999_999), Duration.ofMillis((long) Integer.MAX_VALUE + 1) })
            assertThrows(IllegalArgumentException.class, () -> options(invalid));
    }

    private static ProxyOptions options(Duration responseHeadersTimeout) {
        return new ProxyOptions(URI.create("http://localhost:9000"), 1, 8, 4, 4,
                1024, 4096, Duration.ofSeconds(1), Duration.ofSeconds(2), responseHeadersTimeout,
                Duration.ofMinutes(1), Duration.ofSeconds(30), List.of());
    }
}
