package dev.example.integrationhost;

import com.otr.portal.proxy.caching.CachedResponse;
import com.otr.portal.proxy.caching.SharedResponseCache;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SharedResponseCacheTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @Test void WeightedEvictionBoundsPayloadAndZeroByteEntries() {
        var cache = new SharedResponseCache(2048, 1024, Clock.fixed(NOW, ZoneOffset.UTC));
        for (int i = 0; i < 100; i++) cache.put("entry-" + i, response(512, "public, max-age=60"));
        assertTrue(cache.estimatedWeight() <= 2048);
        assertTrue(cache.size() > 0 && cache.size() <= 3);
        assertNull(cache.get("entry-0"));
        assertTrue(cache.evictionCount() > 0);
        for (int i = 0; i < 100; i++) cache.put("empty-" + i, response(0, "max-age=60"));
        assertTrue(cache.estimatedWeight() <= 2048);
        assertTrue(cache.size() < 20, "zero-byte responses must still consume metadata weight");
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.estimatedWeight());
    }

    @Test void ExpiredEntriesAreNotServedAndAreRemovedWithoutHousekeepingDelay() {
        var clock = new MutableClock();
        var cache = new SharedResponseCache(4096, 1024, clock);
        assertTrue(cache.put("key", response(12, "max-age=10")));
        assertNotNull(cache.get("key"));
        clock.current.set(NOW.plusSeconds(10));
        assertNull(cache.get("key"));
        assertEquals(0, cache.size());
    }

    @Test void PrivateVariantCookiePartialAndUnfreshResponsesAreNotPublished() {
        var cache = new SharedResponseCache(4096, 1024, Clock.fixed(NOW, ZoneOffset.UTC));
        for (String control : List.of("no-store, max-age=30", "no-cache, max-age=30", "private, max-age=30",
                "max-age=0", "max-age=-1", "max-age=nonsense", "max-age=1, max-age=2", "public"))
            assertFalse(cache.put(control, response(12, control)), control);
        for (String name : List.of("Set-Cookie", "Vary", "Content-Range")) {
            CachedResponse response = response(12, "max-age=60");
            response.addHeader(name, "value");
            assertFalse(cache.put(name, response));
        }
        CachedResponse partial = response(12, "max-age=60");
        partial.setStatus(206);
        assertFalse(cache.put("partial", partial));
        assertFalse(cache.put("large", response(1025, "max-age=60")));
        assertEquals(0, cache.size());
    }

    @Test void SharedMaxAgeAndUpstreamAgeAreRespectedAndNoStoreOverridesExpires() {
        var clock = new MutableClock();
        var cache = new SharedResponseCache(4096, 1024, clock);
        CachedResponse response = response(12, "max-age=1, s-maxage=30");
        response.addHeader("Age", "20");
        assertTrue(cache.put("age", response));
        assertEquals(NOW.plusSeconds(10), cache.get("age").getValidThrough().toInstant());
        CachedResponse stale = response(12, "max-age=30");
        stale.addHeader("Date", "Mon, 26 Oct 2026 11:59:00 GMT");
        assertFalse(cache.put("old-date", stale));
        CachedResponse forbidden = response(12, "no-store");
        forbidden.addHeader("Expires", "Mon, 26 Oct 2026 12:01:00 GMT");
        assertFalse(cache.put("no-store", forbidden));
        CachedResponse expires = response(12, "public");
        expires.addHeader("Expires", "Mon, 26 Oct 2026 12:01:00 GMT");
        assertTrue(cache.put("expires", expires));
        CachedResponse agedExpires = response(12, "public");
        agedExpires.addHeader("Date", "Mon, 26 Oct 2026 12:00:00 GMT");
        agedExpires.addHeader("Expires", "Mon, 26 Oct 2026 12:01:00 GMT");
        agedExpires.addHeader("Age", "120");
        assertFalse(cache.put("aged-expires", agedExpires));
    }

    @Test void QueryIsPartOfKeyAndRequestsWithIdentityRangesOrConditionsBypassCache() {
        assertNotEquals(SharedResponseCache.key("backend", "/page", "v=1"),
                SharedResponseCache.key("backend", "/page", "v=2"));
        HttpServletRequest plain = request();
        assertTrue(SharedResponseCache.eligibleRequest(plain));
        for (String name : List.of("Authorization", "Cookie", "Range", "If-None-Match", "If-Modified-Since")) {
            HttpServletRequest guarded = request();
            when(guarded.getHeaderNames()).thenReturn(Collections.enumeration(List.of(name)));
            assertFalse(SharedResponseCache.eligibleRequest(guarded), name);
        }
        for (String value : List.of("no-cache", "no-store", "max-age=0")) {
            HttpServletRequest guarded = request();
            when(guarded.getHeaders("Cache-Control")).thenReturn(Collections.enumeration(List.of(value)));
            assertFalse(SharedResponseCache.eligibleRequest(guarded), value);
        }
        HttpServletRequest post = request();
        when(post.getMethod()).thenReturn("POST");
        assertFalse(SharedResponseCache.eligibleRequest(post));
    }

    @Test void PublishedMetadataCannotBeMutatedToBypassWeightOrFreshness() {
        var cache = new SharedResponseCache(4096, 1024, Clock.fixed(NOW, ZoneOffset.UTC));
        var original = response(12, "max-age=60");
        assertTrue(cache.put("frozen", original));
        long weight = cache.estimatedWeight();
        original.setContent(new byte[64 * 1024]);
        original.addHeader("Vary", "Cookie");
        original.setValidThrough(new java.util.Date(Long.MAX_VALUE));
        var stored = cache.get("frozen");
        assertEquals(12, stored.getContent().length);
        assertEquals(weight, cache.estimatedWeight());
        assertFalse(stored.getHeaders().containsKey("Vary"));
        assertThrows(UnsupportedOperationException.class, () -> stored.setContent(new byte[2048]));
        assertThrows(UnsupportedOperationException.class, () -> stored.getHeaders().put("Vary", "Cookie"));
        stored.getValidThrough().setTime(0);
        assertNotNull(cache.get("frozen"));
    }

    private static CachedResponse response(int bytes, String control) {
        var response = new CachedResponse();
        response.setStatus(200);
        response.setContent(new byte[bytes]);
        response.addHeader("Cache-Control", control);
        return response;
    }

    private static HttpServletRequest request() {
        var request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getHeaders("Cache-Control")).thenReturn(Collections.emptyEnumeration());
        return request;
    }

    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> current = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}
