package dev.example.jettyclient.proxy;

import java.net.URI;
import java.time.Duration;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Dynamic backend configuration must not retain every historical origin indefinitely. */
@Timeout(10)
class JettyProxyDestinationEvictionTest {
    @Test
    void configuredClientAutomaticallyRemovesEmptyHistoricalDestinations() throws Exception {
        JettyProxyProperties properties = new JettyProxyProperties(URI.create("http://127.0.0.1:9000"),
                1024, 16_384, 3, 3, 3, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofMillis(25));
        HttpClient client = new JettyProxyConfiguration().jettyHttpClient(properties);
        try {
            // Resolve without sending: both destinations have no exchanges or connections.
            client.resolveDestination(client.newRequest("http://127.0.0.1:12345/first"));
            client.resolveDestination(client.newRequest("http://127.0.0.1:12346/second"));
            assertEquals(2, client.getDestinations().size());
            assertEquals(25, client.getDestinationIdleTimeout());

            await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                    assertTrue(client.getDestinations().isEmpty(), "The real Jetty sweeper must evict empty origins"));

            var next = client.resolveDestination(client.newRequest("http://127.0.0.1:12345/reused-origin"));
            assertNotNull(next);
            assertEquals(1, client.getDestinations().size(), "An evicted origin remains usable");
        } finally {
            client.stop();
        }
    }
}
