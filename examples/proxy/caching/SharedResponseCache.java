package com.otr.portal.proxy.caching;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Conservative shared cache for explicitly configured public GET routes.
 * Payload and estimated metadata weight are bounded; this is not a JVM RSS limit.
 * Captures and references held by in-flight cache hits have separate lifetimes.
 */
public final class SharedResponseCache {
    public static final long DEFAULT_MAX_WEIGHT = 128L * 1024 * 1024;
    public static final int DEFAULT_MAX_ENTRY_BYTES = 8 * 1024 * 1024;

    private final Cache<String, CachedResponse> entries;
    private final int maxEntryBytes;
    private final Clock clock;

    public SharedResponseCache() { this(DEFAULT_MAX_WEIGHT, DEFAULT_MAX_ENTRY_BYTES, Clock.systemUTC()); }

    public SharedResponseCache(long maxWeight, int maxEntryBytes, Clock clock) {
        if (maxWeight <= 0 || maxEntryBytes <= 0) throw new IllegalArgumentException("Cache limits must be positive");
        this.maxEntryBytes = maxEntryBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
        // Publication has a single worker. One segment gives each entry access to the
        // configured total weight instead of Guava splitting it into smaller per-segment quotas.
        entries = CacheBuilder.newBuilder().concurrencyLevel(1).maximumWeight(maxWeight).recordStats()
                .weigher((String key, CachedResponse value) -> weight(key, value)).build();
    }

    public static String key(String backend, String rawUri, String rawQuery) {
        return backend + '\n' + rawUri + (rawQuery == null ? "" : "?" + rawQuery);
    }

    /** Application-specific identity and route policy must be checked by the caller as well. */
    public static boolean eligibleRequest(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return false;
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement().toLowerCase(Locale.ROOT);
            if (name.equals("authorization") || name.equals("cookie") || name.equals("range")
                    || name.startsWith("if-")) return false;
        }
        var controls = request.getHeaders("Cache-Control");
        while (controls != null && controls.hasMoreElements()) {
            for (String directive : controls.nextElement().split(",")) {
                String name = directive.split("=", 2)[0].strip().toLowerCase(Locale.ROOT);
                if (name.equals("no-cache") || name.equals("no-store") || name.equals("max-age")) return false;
            }
        }
        return request.getHeader("Pragma") == null;
    }

    public CachedResponse get(String key) {
        CachedResponse result = entries.getIfPresent(key);
        if (result != null && (result.getValidThrough() == null
                || !result.getValidThrough().toInstant().isAfter(clock.instant()))) {
            entries.asMap().remove(key, result);
            return null;
        }
        return result;
    }

    /**
     * Called only after successful capture completion, on a bounded publication executor.
     * Metadata is frozen. Ownership of the completed body array is transferred: neither
     * the caller nor cache readers may mutate its contents. No additional body copy occurs.
     */
    public synchronized boolean put(String key, CachedResponse value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (value.getStatus() != 200 || value.getContent() == null || value.getContent().length > maxEntryBytes
                || value.getHeaders().containsKey("Set-Cookie") || value.getHeaders().containsKey("Vary")
                || value.getHeaders().containsKey("Content-Range")) return false;
        Instant freshUntil = freshness(value);
        if (freshUntil == null || !freshUntil.isAfter(clock.instant())) return false;
        CachedResponse stored = new StoredResponse(value, freshUntil);
        entries.put(key, stored);
        return entries.getIfPresent(key) == stored;
    }

    public void cleanUp() {
        entries.asMap().forEach((key, value) -> {
            if (value.getValidThrough() == null || !value.getValidThrough().toInstant().isAfter(clock.instant()))
                entries.asMap().remove(key, value);
        });
        entries.cleanUp();
    }

    public long size() { return entries.size(); }

    /** Entries removed by capacity eviction, distinct from explicit invalidation. */
    public long evictionCount() { return entries.stats().evictionCount(); }

    /** Releases stored references; active writers retain their borrowed bodies until completion. */
    public void clear() {
        entries.invalidateAll();
        entries.cleanUp();
    }

    /** Serialize with publication so a snapshot cannot count both replaced and replacing entries. */
    public synchronized long estimatedWeight() {
        return entries.asMap().entrySet().stream().mapToLong(entry -> weight(entry.getKey(), entry.getValue())).sum();
    }

    private Instant freshness(CachedResponse value) {
        Long maxAge = null;
        Long sharedMaxAge = null;
        try {
            for (String header : value.getHeaders().get("Cache-Control")) {
                for (String directive : header.split(",")) {
                    String[] parts = directive.strip().split("=", 2);
                    String name = parts[0].strip().toLowerCase(Locale.ROOT);
                    if (name.equals("no-store") || name.equals("no-cache") || name.equals("private")) return null;
                    if (name.equals("max-age") || name.equals("s-maxage")) {
                        if (parts.length != 2) return null;
                        String seconds = parts[1].strip();
                        if (seconds.startsWith("\"") && seconds.endsWith("\"") && seconds.length() >= 2)
                            seconds = seconds.substring(1, seconds.length() - 1);
                        long parsed = Long.parseLong(seconds);
                        if (parsed < 0) return null;
                        if (name.equals("s-maxage")) {
                            if (sharedMaxAge != null) return null;
                            sharedMaxAge = parsed;
                        } else {
                            if (maxAge != null) return null;
                            maxAge = parsed;
                        }
                    }
                }
            }
            Long lifetime = sharedMaxAge == null ? maxAge : sharedMaxAge;
            Instant now = clock.instant();
            Instant responseDate = date(value.getHeaders().get("Date"));
            long apparentAge = responseDate == null ? 0 : Math.max(0, now.getEpochSecond() - responseDate.getEpochSecond());
            Collection<String> ageHeaders = value.getHeaders().get("Age");
            if (ageHeaders.size() > 1) return null;
            long age = ageHeaders.isEmpty() ? 0 : Long.parseLong(ageHeaders.iterator().next().strip());
            if (age < 0) return null;
            if (lifetime == null) {
                Instant expires = date(value.getHeaders().get("Expires"));
                if (expires == null) return null;
                // Without Date the origin's Expires is usable only when no inherited Age needs adjustment.
                if (responseDate == null) return ageHeaders.isEmpty() ? expires : null;
                lifetime = Math.subtractExact(expires.getEpochSecond(), responseDate.getEpochSecond());
            }
            long remaining = Math.subtractExact(lifetime, Math.max(age, apparentAge));
            return remaining > 0 ? now.plusSeconds(remaining) : null;
        } catch (NumberFormatException | DateTimeException | ArithmeticException invalid) {
            return null;
        }
    }

    private static Instant date(Collection<String> values) {
        if (values.size() != 1) return null;
        return ZonedDateTime.parse(values.iterator().next(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    }

    private static int weight(String key, CachedResponse value) {
        long weight = 128L + 2L * key.length() + value.getContent().length;
        for (Map.Entry<String, String> header : value.getHeaders().entries())
            weight += 64L + 2L * (header.getKey().length() + header.getValue().length());
        return (int) Math.min(Integer.MAX_VALUE, weight);
    }

    /** Keep mutable legacy DTO setters away from the weighted entry after publication. */
    private static final class StoredResponse extends CachedResponse {
        private final Multimap<String, String> headers;
        private final byte[] content;
        private final int status;
        private final long validThrough;

        StoredResponse(CachedResponse source, Instant expires) {
            Multimap<String, String> snapshot = MultimapBuilder.treeKeys(String.CASE_INSENSITIVE_ORDER)
                    .arrayListValues().build();
            snapshot.putAll(source.getHeaders());
            headers = Multimaps.unmodifiableMultimap(snapshot);
            content = source.getContent();
            status = source.getStatus();
            validThrough = expires.toEpochMilli();
        }

        @Override public Multimap<String, String> getHeaders() { return headers; }
        /** Borrowed immutable array: writers may read it but must never modify it. */
        @Override public byte[] getContent() { return content; }
        @Override public int getStatus() { return status; }
        @Override public Date getValidThrough() { return new Date(validThrough); }
        @Override public boolean invalid() { return validThrough <= System.currentTimeMillis(); }
        @Override public void addHeader(String name, String value) { throw frozen(); }
        @Override public void setContent(byte[] bytes) { throw frozen(); }
        @Override public void setStatus(int code) { throw frozen(); }
        @Override public void setValidThrough(Date date) { throw frozen(); }
        private static UnsupportedOperationException frozen() { return new UnsupportedOperationException("Cached response is immutable"); }
    }
}
