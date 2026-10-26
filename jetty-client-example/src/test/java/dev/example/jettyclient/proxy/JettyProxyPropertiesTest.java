package dev.example.jettyclient.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class JettyProxyPropertiesTest {
    private static final Duration MINIMUM = Duration.ofMillis(1);

    @Test
    void rejectsExchangeTimeoutThatFitsMillisecondsButOverflowsNanoseconds() {
        Duration timeout = Duration.ofNanos(Long.MAX_VALUE).plusNanos(1);
        assertDoesNotThrow(() -> { timeout.toMillis(); });

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> properties(MINIMUM, timeout, MINIMUM));

        assertTrue(failure.getMessage().contains("exchange-timeout"));
        assertInstanceOf(ArithmeticException.class, failure.getCause());
    }

    @Test
    void acceptsLargestExchangeTimeoutRepresentableInNanoseconds() {
        Duration timeout = Duration.ofNanos(Long.MAX_VALUE);

        assertEquals(timeout, properties(MINIMUM, timeout, MINIMUM).exchangeTimeout());
    }

    @Test
    void keepsMillisecondOnlyLimitsForConnectAndIdleTimeouts() {
        Duration timeout = Duration.ofNanos(Long.MAX_VALUE).plusNanos(1);

        JettyProxyProperties properties = properties(timeout, MINIMUM, timeout);

        assertEquals(timeout, properties.connectTimeout());
        assertEquals(timeout, properties.idleTimeout());
    }

    @Test
    void acceptsOneMillisecondForEveryTimeout() {
        JettyProxyProperties properties = properties(MINIMUM, MINIMUM, MINIMUM);

        assertEquals(MINIMUM, properties.connectTimeout());
        assertEquals(MINIMUM, properties.exchangeTimeout());
        assertEquals(MINIMUM, properties.idleTimeout());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonpositiveResponseHeadersLimit(int limit) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new JettyProxyProperties(URI.create("http://127.0.0.1:9000"),
                        65_536, limit, 128, 64, 64, 2, MINIMUM, MINIMUM, MINIMUM));
        assertTrue(failure.getMessage().contains("max-response-headers-bytes"));
    }

    @ParameterizedTest
    @MethodSource("invalidTimeouts")
    void rejectsNonpositiveAndSubmillisecondTimeoutsWithTheirPropertyNames(Duration timeout) {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> properties(timeout, MINIMUM, MINIMUM)).getMessage().contains("connect-timeout"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> properties(MINIMUM, timeout, MINIMUM)).getMessage().contains("exchange-timeout"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> properties(MINIMUM, MINIMUM, timeout)).getMessage().contains("idle-timeout"));
    }

    private static Stream<Duration> invalidTimeouts() {
        return Stream.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(999_999));
    }

    private static JettyProxyProperties properties(Duration connect, Duration exchange, Duration idle) {
        return new JettyProxyProperties(URI.create("http://127.0.0.1:9000"),
                65_536, 16_384, 128, 64, 64, 2, connect, exchange, idle);
    }
}
