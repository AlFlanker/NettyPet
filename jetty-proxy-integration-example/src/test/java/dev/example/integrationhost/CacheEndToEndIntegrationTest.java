package dev.example.integrationhost;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end misses really populate the same cache that serves the following HTTP hit. */
@Timeout(30)
class CacheEndToEndIntegrationTest {
    private static final long MIB = 1024L * 1024;

    @Test
    void missPublishesEightMebibytesAndNextHttpHitDoesNotContactTheOrigin() throws Exception {
        try (var server = new CacheIntegrationServer(); var client = client()) {
            String path = "/data/complete?version=1";
            assertBody(get(client, server, path), path, "MISS");
            awaitStored(server, path);
            long originRequests = server.stats().upstreamRequests();
            assertBody(get(client, server, path), path, "HIT");
            server.awaitIdle(Duration.ofSeconds(5));
            assertEquals(1, originRequests);
            assertEquals(originRequests, server.stats().upstreamRequests(), "The hit must make no second backend request");
            assertEquals(1, server.stats().stored());
            assertEquals(1, server.stats().hits());
            assertEquals(0, server.stats().captureReserved());
            assertEquals(0, server.stats().publicationFailures());
        }
    }

    @Test
    void rawQueryVariantsHaveIndependentMissPublishHitCycles() throws Exception {
        try (var server = new CacheIntegrationServer(); var client = client()) {
            String first = "/data/query?value=one&encoded=%2F";
            String second = "/data/query?value=two&encoded=%252F";
            for (String path : new String[]{first, second}) {
                assertBody(get(client, server, path), path, "MISS");
                awaitStored(server, path);
            }
            for (String path : new String[]{first, second}) assertBody(get(client, server, path), path, "HIT");
            server.awaitIdle(Duration.ofSeconds(5));
            assertEquals(2, server.stats().upstreamRequests());
            assertEquals(2, server.stats().hits());
        }
    }

    @Test
    void expiredEntryTriggersAnotherOriginRequestWithoutWaitingForHousekeeping() throws Exception {
        MutableClock clock = new MutableClock();
        try (var server = new CacheIntegrationServer(24 * MIB, 32 * MIB, CacheIntegrationServer.BODY_BYTES, clock);
             var client = client()) {
            String path = "/short-lived/page";
            assertBody(get(client, server, path), path, "MISS");
            awaitStored(server, path);
            clock.advance(Duration.ofSeconds(61));
            assertFalse(server.isCached(path), "Reading at the advanced clock must remove the expired entry");
            // The independent JDK origin always supplies a real-time Date header. Reset the test
            // clock after proving removal so the newly fetched representation has its normal TTL.
            clock.resetToNow();
            assertBody(get(client, server, path), path, "MISS");
            awaitStored(server, path);
            assertBody(get(client, server, path), path, "HIT");
            server.awaitIdle(Duration.ofSeconds(5));
            assertEquals(2, server.stats().upstreamRequests());
            assertEquals(1, server.stats().hits());
        }
    }

    @Test
    void evictedEntryIsFetchedAgainAndTheRealStoreStaysWithinItsWeight() throws Exception {
        try (var server = new CacheIntegrationServer(9 * MIB, 32 * MIB, CacheIntegrationServer.BODY_BYTES, Clock.systemUTC());
             var client = client()) {
            String first = "/data/evict-first";
            String second = "/data/evict-second";
            assertBody(get(client, server, first), first, "MISS");
            awaitStored(server, first);
            assertBody(get(client, server, second), second, "MISS");
            awaitStored(server, second);
            assertFalse(server.isCached(first));
            assertBody(get(client, server, first), first, "MISS");
            awaitStored(server, first);
            server.awaitIdle(Duration.ofSeconds(5));
            assertEquals(3, server.stats().upstreamRequests());
            assertTrue(server.stats().evictions() >= 2);
            assertTrue(server.stats().cacheWeight() <= 9 * MIB);
        }
    }

    @Test
    void uncacheableResponseIsDeliveredCorrectlyAndAlwaysReturnsToOrigin() throws Exception {
        try (var server = new CacheIntegrationServer(); var client = client()) {
            String path = "/no-store/private";
            for (int i = 0; i < 2; i++) {
                assertBody(get(client, server, path), path, "MISS");
                server.awaitIdle(Duration.ofSeconds(5));
                assertFalse(server.isCached(path));
            }
            assertEquals(2, server.stats().upstreamRequests());
            assertEquals(0, server.stats().cacheEntries());
            assertEquals(0, server.stats().captureReserved());
        }
    }

    @Test
    void truncatedOriginBodyNeverBecomesAHitOrRetainsACaptureReservation() throws Exception {
        try (var server = new CacheIntegrationServer(); var client = client()) {
            String path = "/truncated/resource";
            for (int i = 0; i < 2; i++) {
                assertThrows(IOException.class, () -> get(client, server, path));
                server.awaitIdle(Duration.ofSeconds(5));
                assertFalse(server.isCached(path));
                assertEquals(0, server.stats().captureReserved());
            }
            assertEquals(2, server.stats().upstreamRequests());
            assertEquals(0, server.stats().hits());
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    private static HttpResponse<byte[]> get(HttpClient client, CacheIntegrationServer server, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assertBody(HttpResponse<byte[]> response, String path, String disposition) throws Exception {
        assertEquals(200, response.statusCode());
        assertEquals(disposition, response.headers().firstValue("X-Cache").orElseThrow());
        assertEquals(path, response.headers().firstValue("X-Origin-Key").orElseThrow());
        assertEquals("application/octet-stream", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(CacheIntegrationServer.BODY_BYTES, response.body().length);
        assertEquals(CacheIntegrationServer.expectedSha256(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body())));
    }

    private static void awaitStored(CacheIntegrationServer server, String path) {
        await().atMost(Duration.ofSeconds(5)).until(() -> server.isCached(path));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.now());
        void advance(Duration amount) { instant.updateAndGet(value -> value.plus(amount)); }
        void resetToNow() { instant.set(Instant.now()); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
