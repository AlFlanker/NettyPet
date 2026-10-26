package dev.example.integrationhost;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/** Standalone, isolated-JVM HTTP soak; deliberately not a unit test or a production capacity benchmark. */
public final class CacheSoakRunner {
    private static final long MIB = 1024L * 1024;
    private static final long STORE_BYTES = 48 * MIB;
    private static final long CAPTURE_BYTES = 64 * MIB;
    private static final long RETAINED_TOLERANCE = 64 * MIB;
    private static final double MAX_GC_TIME_RATIO = 0.35;
    private static final int SAMPLE_SECONDS = 5;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> CSV_COLUMNS = List.of("phase", "elapsedSeconds", "completed", "bytes",
            "expectedAborts", "errors", "heapUsedBytes", "heapCommittedBytes", "heapMaxBytes", "postGcHeapBytes",
            "postGcEventSequence", "postGcFromMeasurement", "gcCount", "gcTimeMillis", "hits", "misses", "upstreamRequests", "stored",
            "rejected", "publicationFailures", "activeHits", "engineActive", "captureReserved", "cacheWeight",
            "cacheEntries", "publicationActive", "publicationQueued", "evictions", "publicationBypassed");

    private final Settings settings;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong coldKey = new AtomicLong();
    private final Counters counters = new Counters();
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final ScheduledThreadPoolExecutor bodyDeadlines = new ScheduledThreadPoolExecutor(2,
            Thread.ofPlatform().name("soak-body-deadline-", 0).daemon(true).factory());

    private CacheSoakRunner(Settings settings) {
        this.settings = settings;
        // There is at most one live deadline per reader. Cancelled tasks must not retain response streams.
        bodyDeadlines.setRemoveOnCancelPolicy(true);
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.parse(args);
        Files.createDirectories(settings.outputDirectory());
        new CacheSoakRunner(settings).run();
    }

    private void run() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", Instant.now().toString());
        report.put("sourceCommit", settings.sourceCommit());
        report.put("jdk", System.getProperty("java.runtime.version"));
        report.put("vm", System.getProperty("java.vm.name"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("settings", settingsMap());
        CacheIntegrationServer server = null;
        HttpClient client = null;
        ExecutorService httpWorkers = Executors.newFixedThreadPool(8,
                Thread.ofPlatform().name("soak-http-", 0).daemon(true).factory());
        ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
        List<Long> baseline = List.of();
        long loadStarted = 0;
        Counts measurementStart = null;
        Counts measurementEnd = null;
        CacheIntegrationServer.Stats startStats = null;
        CacheIntegrationServer.Stats endStats = null;
        GcTotals startGc = null;
        GcTotals endGc = null;
        double measuredSeconds = 0;

        try (GcMonitor gc = new GcMonitor();
             BufferedWriter csv = Files.newBufferedWriter(settings.outputDirectory().resolve("samples.csv"), StandardCharsets.UTF_8)) {
            csv.write(String.join(",", CSV_COLUMNS));
            csv.newLine();
            try {
                server = new CacheIntegrationServer(STORE_BYTES, CAPTURE_BYTES, CacheIntegrationServer.BODY_BYTES, Clock.systemUTC());
                client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(httpWorkers)
                        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
                prime(server, client);
                server.clearCache();
                server.awaitIdle(Duration.ofSeconds(10));
                baseline = retainedSamples();
                report.put("baselineAfterGcBytes", baseline);

                final CacheIntegrationServer activeServer = server;
                final HttpClient activeClient = client;
                loadStarted = System.nanoTime();
                long measurementBegins = loadStarted + TimeUnit.SECONDS.toNanos(settings.warmupSeconds());
                long loadEnds = measurementBegins + TimeUnit.SECONDS.toNanos(settings.measurementSeconds());
                for (int id = 0; id < settings.concurrency(); id++) {
                    final int worker = id;
                    readers.submit(() -> work(activeServer, activeClient, worker, loadEnds));
                }
                long nextSample = loadStarted;
                long actualMeasurementStart = 0;
                while (System.nanoTime() < loadEnds && failures.isEmpty()) {
                    long now = System.nanoTime();
                    if (measurementStart == null && now >= measurementBegins) {
                        measurementStart = counters.snapshot();
                        startStats = server.stats();
                        startGc = gcTotals();
                        actualMeasurementStart = now;
                        gc.measuring = true;
                    }
                    if (now >= nextSample) {
                        sample(server, gc, csv, measurementStart == null ? "warmup" : "steady", loadStarted);
                        nextSample += TimeUnit.SECONDS.toNanos(SAMPLE_SECONDS);
                    }
                    TimeUnit.MILLISECONDS.sleep(100);
                }
                long measurementFinished = System.nanoTime();
                measurementEnd = counters.snapshot();
                endStats = server.stats();
                endGc = gcTotals();
                gc.measuring = false;
                measuredSeconds = actualMeasurementStart == 0 ? 0 : (measurementFinished - actualMeasurementStart) / 1_000_000_000.0;
                running.set(false);
                sample(server, gc, csv, "measurement-end", loadStarted);
                readers.shutdown();
                require(readers.awaitTermination(30, TimeUnit.SECONDS), "HTTP readers did not finish within the request deadline");
                server.awaitIdle(Duration.ofSeconds(20));
                CacheIntegrationServer.Stats drained = server.stats();
                requireIdle(drained);
                report.put("afterDrain", statsMap(drained));
                report.put("allRequestsIncludingWarmupAndDrain", countsMap(counters.snapshot()));
                List<Long> retainedWithCache = retainedSamples();
                report.put("afterDrainWithCacheGcBytes", retainedWithCache);
                long baselineMinimum = minimum(baseline);
                require(minimum(retainedWithCache) <= baselineMinimum + STORE_BYTES + RETAINED_TOLERANCE,
                        "Quiescent retained heap exceeded baseline + store budget + 64MiB tolerance");
                if (measurementStart == null) fail("Measurement phase was not reached");
                else {
                    Counts delta = measurementEnd.subtract(measurementStart);
                    report.put("measurement", measurementMap(delta, startStats, endStats, startGc, endGc,
                            measuredSeconds, gc.maxMeasuredGcMillis.get()));
                    require(delta.completed() >= settings.concurrency(), "Too few completed responses in measurement");
                    require(endStats.hits() - startStats.hits() >= 10, "Measurement did not exercise enough actual cache hits");
                    require(endStats.misses() - startStats.misses() >= 10, "Measurement did not exercise enough actual cache misses");
                    require(endStats.evictions() - startStats.evictions() >= 5, "Measurement did not exercise repeated eviction");
                    require(delta.expectedAborts() > 0, "Measurement did not exercise intentional disconnects");
                    require(delta.abortedHits() > 0 && delta.abortedMisses() > 0,
                            "Intentional disconnects must exercise both actual cache hits and misses");
                    require(delta.slowCompleted() > 0, "Slow readers did not complete verified bodies");
                    require(delta.completedHits() > 0 && delta.completedMisses() > 0, "Both HIT and MISS bodies must pass SHA-256");
                    require(endGc.count() > startGc.count() && gc.measuredEvents.get() > 0,
                            "No measured GC events were available to assess heap/GC under load");
                    double gcRatio = (endGc.millis() - startGc.millis()) / (measuredSeconds * 1000);
                    require(gcRatio <= MAX_GC_TIME_RATIO, "Collector-reported GC time exceeded 35% of measurement time");
                }
                require(server.stats().publicationFailures() == 0, "Unexpected cache publication failure");
                report.put("steadyPostGcTrend", postGcTrend());
            } catch (Throwable failure) {
                fail(failure.getClass().getName() + ": " + String.valueOf(failure.getMessage()));
            } finally {
                running.set(false);
                readers.shutdownNow();
                if (client != null) client.shutdownNow();
                bodyDeadlines.shutdownNow();
                try { readers.awaitTermination(25, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fail("Cleanup interrupted"); }
                if (server != null) {
                    try {
                        server.close();
                        requireIdle(server.stats());
                        require(server.stats().cacheWeight() == 0, "Cache did not clear on shutdown");
                        report.put("afterClose", statsMap(server.stats()));
                    } catch (Throwable failure) { fail("Server cleanup: " + failure.getClass().getName() + ": " + failure.getMessage()); }
                }
                httpWorkers.shutdownNow();
                try {
                    httpWorkers.awaitTermination(10, TimeUnit.SECONDS);
                    require(bodyDeadlines.awaitTermination(5, TimeUnit.SECONDS), "Body deadline executor did not stop");
                }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fail("HTTP executor cleanup interrupted"); }
                try {
                    List<Long> closed = retainedSamples();
                    report.put("afterCloseGcBytes", closed);
                    if (!baseline.isEmpty()) require(minimum(closed) <= minimum(baseline) + RETAINED_TOLERANCE,
                            "Retained heap after close exceeded baseline + 64MiB tolerance");
                } catch (Throwable failure) { fail("Final retained-heap check: " + failure); }
                String diagnosticPath = System.getProperty("cacheSoak.dumpHeapOnFailure");
                if (!failures.isEmpty() && diagnosticPath != null && !diagnosticPath.isBlank()) {
                    try {
                        Path dump = Path.of(diagnosticPath).toAbsolutePath();
                        if (dump.getParent() != null) Files.createDirectories(dump.getParent());
                        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(dump.toString(), true);
                        report.put("failureHeapDump", dump.toString());
                    } catch (Exception diagnosticFailure) {
                        report.put("failureHeapDumpError", diagnosticFailure.toString());
                    }
                }
                report.put("success", failures.isEmpty());
                report.put("failures", List.copyOf(failures));
                report.put("samples", samples);
                report.put("finishedAt", Instant.now().toString());
                report.put("limitsOfEvidence", List.of(
                        "Proxy, origin and load generator share this JVM and machine; throughput is not a production capacity claim",
                        "Every complete response verifies status, raw origin key, body length and SHA-256; intentional disconnects are separate",
                        "No explicit GC occurs during warmup or measurement; explicit GC is used only for baseline and drained/closed comparisons",
                        "postGcHeapBytes is one GC notification's heap-pool snapshot, not an RSS measurement",
                        "GC time ratio uses GarbageCollectorMXBean reported collection time and is not a precise stop-the-world pause ratio",
                        "Store/capture bounds exclude other heap users and body references held by active hits; concurrency is bounded by this runner"));
                Files.writeString(settings.outputDirectory().resolve("report.json"), json(report) + "\n", StandardCharsets.UTF_8);
            }
        }
        System.out.printf(Locale.ROOT, "[cache-soak] success=%s completed=%d expectedAborts=%d failures=%d report=%s%n",
                failures.isEmpty(), counters.completed.sum(), counters.expectedAborts.sum(), failures.size(),
                settings.outputDirectory().resolve("report.json"));
        if (!failures.isEmpty()) throw new IllegalStateException("Cache soak failed: " + failures);
    }

    private void prime(CacheIntegrationServer server, HttpClient client) throws Exception {
        long before = server.stats().upstreamRequests();
        perform(server, client, "/data/prime", Mode.FAST, false, new byte[64 * 1024], MessageDigest.getInstance("SHA-256"));
        server.awaitIdle(Duration.ofSeconds(10));
        require(server.isCached("/data/prime"), "Preflight miss was not published");
        perform(server, client, "/data/prime", Mode.FAST, false, new byte[64 * 1024], MessageDigest.getInstance("SHA-256"));
        server.awaitIdle(Duration.ofSeconds(10));
        require(server.stats().upstreamRequests() - before == 1, "Preflight hit sent a second request to the origin");
    }

    private void work(CacheIntegrationServer server, HttpClient client, int id, long ends) {
        try {
            Mode mode = id >= settings.concurrency() - settings.abortReaders() ? Mode.ABORT
                    : id >= settings.fastReaders() ? Mode.SLOW : Mode.FAST;
            byte[] buffer = new byte[64 * 1024];
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long iteration = 0;
            while (running.get() && System.nanoTime() < ends) {
                boolean cold = mode == Mode.ABORT ? iteration % 2 == 1
                        : mode == Mode.SLOW ? iteration % 3 == 2 : iteration % 5 == 4;
                String path;
                if (cold) {
                    long key = coldKey.getAndIncrement();
                    path = "/data/cold-" + key + "?v=" + key % 7;
                } else path = "/data/hot-" + ((id + iteration) & 1) + "?v=0";
                perform(server, client, path, mode, true, buffer, digest);
                iteration++;
            }
        } catch (Throwable failure) {
            if (running.get() || !(failure instanceof InterruptedException))
                fail("Worker " + id + ": " + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private void perform(CacheIntegrationServer server, HttpClient client, String path, Mode mode, boolean count,
                         byte[] buffer, MessageDigest digest) throws Exception {
        long deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();
        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(server.baseUri().resolve(path))
                .timeout(REQUEST_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        AtomicBoolean expired = new AtomicBoolean();
        // HttpRequest.timeout ends at headers with ofInputStream; explicitly bound the body lifetime as well.
        var timeout = bodyDeadlines.schedule(() -> {
            expired.set(true);
            try { response.body().close(); }
            catch (IOException ignored) { }
        }, Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Unexpected status " + response.statusCode());
            if (!response.headers().firstValue("X-Origin-Key").orElse("").equals(path))
                throw new IOException("Wrong cached origin key for " + path);
            String marker = response.headers().firstValue("X-Cache").orElse("");
            if (!marker.equals("HIT") && !marker.equals("MISS")) throw new IOException("Unexpected cache marker " + marker);
            if (mode == Mode.ABORT) {
                int received = body.read(buffer);
                if (expired.get()) throw new IOException("Response body deadline exceeded");
                if (received <= 0) throw new IOException("Abort fixture did not receive response data");
                if (count) {
                    counters.expectedAborts.increment();
                    if (marker.equals("HIT")) counters.abortedHits.increment();
                    else counters.abortedMisses.increment();
                }
                return;
            }
            digest.reset();
            long bytes = 0;
            for (int read; (read = body.read(buffer)) != -1;) {
                if (expired.get()) throw new IOException("Response body deadline exceeded");
                digest.update(buffer, 0, read);
                bytes += read;
                if (bytes > CacheIntegrationServer.BODY_BYTES) throw new IOException("Response exceeds expected body size");
                if (mode == Mode.SLOW) TimeUnit.MILLISECONDS.sleep(1);
            }
            if (expired.get()) throw new IOException("Response body deadline exceeded");
            if (bytes != CacheIntegrationServer.BODY_BYTES || !HexFormat.of().formatHex(digest.digest())
                    .equals(CacheIntegrationServer.expectedSha256())) throw new IOException("Body length or SHA-256 mismatch");
            if (count) {
                counters.bytes.add(bytes);
                counters.completed.increment();
                if (marker.equals("HIT")) counters.completedHits.increment();
                else counters.completedMisses.increment();
                if (mode == Mode.SLOW) counters.slowCompleted.increment();
            }
        } finally { timeout.cancel(false); }
    }

    private void sample(CacheIntegrationServer server, GcMonitor gc, BufferedWriter csv, String phase, long started)
            throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("phase", phase);
        value.put("elapsedSeconds", (System.nanoTime() - started) / 1_000_000_000.0);
        value.putAll(countsMap(counters.snapshot()));
        value.put("errors", failures.size());
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        value.put("heapUsedBytes", heap.getUsed());
        value.put("heapCommittedBytes", heap.getCommitted());
        value.put("heapMaxBytes", heap.getMax());
        GcSnapshot after = gc.latest.get();
        value.put("postGcHeapBytes", after == null ? -1 : after.heapBytes());
        value.put("postGcEventSequence", after == null ? 0 : after.sequence());
        value.put("postGcFromMeasurement", after != null && after.measured());
        GcTotals totals = gcTotals();
        value.put("gcCount", totals.count());
        value.put("gcTimeMillis", totals.millis());
        CacheIntegrationServer.Stats state = server.stats();
        value.putAll(statsMap(state));
        require(state.captureReserved() <= CAPTURE_BYTES, "Capture reservation exceeds configured budget");
        require(state.cacheWeight() <= STORE_BYTES, "Stored cache weight exceeds configured budget");
        require(state.publicationQueued() <= 4 && state.publicationActive() <= 1, "Publication queue/worker limit exceeded");
        require(state.publicationFailures() == 0, "Publication failures observed during load");
        samples.add(value);
        for (int i = 0; i < CSV_COLUMNS.size(); i++) {
            if (i > 0) csv.write(',');
            csv.write(String.valueOf(value.get(CSV_COLUMNS.get(i))));
        }
        csv.newLine();
        csv.flush();
        System.out.printf(Locale.ROOT, "[cache-soak] phase=%s seconds=%.1f complete=%d abort=%d hit=%d miss=%d eviction=%d heapMiB=%.1f postGcMiB=%.1f reserveMiB=%.1f%n",
                phase, value.get("elapsedSeconds"), counters.completed.sum(), counters.expectedAborts.sum(),
                state.hits(), state.misses(), state.evictions(), heap.getUsed() / (double) MIB,
                after == null ? -1 : after.heapBytes() / (double) MIB, state.captureReserved() / (double) MIB);
    }

    private static Map<String, Object> statsMap(CacheIntegrationServer.Stats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hits", s.hits()); m.put("misses", s.misses()); m.put("upstreamRequests", s.upstreamRequests());
        m.put("stored", s.stored()); m.put("rejected", s.rejected()); m.put("publicationFailures", s.publicationFailures());
        m.put("activeHits", s.activeHits()); m.put("engineActive", s.engineActive()); m.put("captureReserved", s.captureReserved());
        m.put("cacheWeight", s.cacheWeight()); m.put("cacheEntries", s.cacheEntries()); m.put("publicationActive", s.publicationActive());
        m.put("publicationQueued", s.publicationQueued()); m.put("evictions", s.evictions()); m.put("publicationBypassed", s.publicationBypassed());
        m.put("publicationPending", s.publicationPending()); m.put("pendingPublicationCallbacks", s.pendingPublicationCallbacks());
        return m;
    }

    private static Map<String, Object> countsMap(Counts c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("completed", c.completed()); m.put("bytes", c.bytes()); m.put("expectedAborts", c.expectedAborts());
        m.put("completedHits", c.completedHits()); m.put("completedMisses", c.completedMisses());
        m.put("abortedHits", c.abortedHits()); m.put("abortedMisses", c.abortedMisses()); m.put("slowCompleted", c.slowCompleted());
        return m;
    }

    private Map<String, Object> settingsMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("warmupSeconds", settings.warmupSeconds()); m.put("measurementSeconds", settings.measurementSeconds());
        m.put("concurrency", settings.concurrency()); m.put("fastReaders", settings.fastReaders());
        m.put("slowReaders", settings.slowReaders()); m.put("abortReaders", settings.abortReaders());
        m.put("bodyBytes", CacheIntegrationServer.BODY_BYTES); m.put("slowDelayMillisPerRead", 1);
        m.put("readBufferBytes", 65536); m.put("sampleSeconds", SAMPLE_SECONDS); m.put("storeMaxWeight", STORE_BYTES);
        m.put("originWorkerThreads", CacheIntegrationServer.ORIGIN_WORKERS);
        m.put("originWriteChunkBytes", CacheIntegrationServer.ORIGIN_CHUNK_BYTES);
        m.put("captureBudgetBytes", CAPTURE_BYTES); m.put("publicationWorkers", 1); m.put("publicationQueue", 4);
        m.put("postDrainRetainedGrowthLimitBytes", STORE_BYTES + RETAINED_TOLERANCE);
        m.put("postCloseRetainedGrowthLimitBytes", RETAINED_TOLERANCE); m.put("maxCollectorTimeRatio", MAX_GC_TIME_RATIO);
        return m;
    }

    private static Map<String, Object> measurementMap(Counts delta, CacheIntegrationServer.Stats from,
            CacheIntegrationServer.Stats to, GcTotals first, GcTotals last, double seconds, long maxGcMillis) {
        Map<String, Object> m = new LinkedHashMap<>(countsMap(delta));
        m.put("elapsedSeconds", seconds); m.put("completeResponsesPerSecond", delta.completed() / seconds);
        m.put("hits", to.hits() - from.hits()); m.put("misses", to.misses() - from.misses());
        m.put("upstreamRequests", to.upstreamRequests() - from.upstreamRequests());
        m.put("evictions", to.evictions() - from.evictions()); m.put("stored", to.stored() - from.stored());
        m.put("gcCount", last.count() - first.count()); m.put("collectorTimeMillis", last.millis() - first.millis());
        m.put("collectorTimeRatio", (last.millis() - first.millis()) / (seconds * 1000));
        m.put("maxNotifiedGcDurationMillis", maxGcMillis);
        return m;
    }

    private Map<String, Object> postGcTrend() {
        List<Map<String, Object>> steady = samples.stream().filter(s -> Boolean.TRUE.equals(s.get("postGcFromMeasurement"))
                && ((Number) s.get("postGcHeapBytes")).longValue() >= 0).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleCount", steady.size());
        result.put("distinctGcEvents", steady.stream().map(s -> s.get("postGcEventSequence")).distinct().count());
        if (steady.size() >= 3) {
            int width = Math.max(1, steady.size() / 3);
            long first = steady.subList(0, width).stream().mapToLong(s -> ((Number) s.get("postGcHeapBytes")).longValue()).min().orElseThrow();
            long last = steady.subList(steady.size() - width, steady.size()).stream().mapToLong(s -> ((Number) s.get("postGcHeapBytes")).longValue()).min().orElseThrow();
            result.put("firstThirdMinimumBytes", first); result.put("lastThirdMinimumBytes", last);
            result.put("minimumGrowthBytes", last - first);
        }
        result.put("interpretation", "Diagnostic lower envelopes under live workload; pass/fail retained-heap bounds use drained and closed states");
        return result;
    }

    private void requireIdle(CacheIntegrationServer.Stats s) {
        require(s.engineActive() == 0 && s.activeHits() == 0 && s.captureReserved() == 0
                && s.publicationActive() == 0 && s.publicationQueued() == 0
                && s.publicationPending() == 0 && s.pendingPublicationCallbacks() == 0,
                "Resources did not drain: " + s);
    }

    private void require(boolean condition, String message) { if (!condition) fail(message); }
    private void fail(String message) {
        synchronized (failures) { if (failures.size() < 16) failures.add(message); }
    }

    private static List<Long> retainedSamples() throws InterruptedException {
        List<Long> values = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            System.gc();
            TimeUnit.MILLISECONDS.sleep(250);
            values.add(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        }
        return values;
    }

    private static long minimum(List<Long> values) { return values.stream().mapToLong(Long::longValue).min().orElseThrow(); }
    private static GcTotals gcTotals() {
        long count = 0, millis = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount()); millis += Math.max(0, bean.getCollectionTime());
        }
        return new GcTotals(count, millis);
    }

    private enum Mode { FAST, SLOW, ABORT }
    private record GcTotals(long count, long millis) { }
    private record GcSnapshot(long sequence, long heapBytes, boolean measured) { }
    private record Counts(long completed, long bytes, long expectedAborts, long completedHits, long completedMisses,
                          long abortedHits, long abortedMisses, long slowCompleted) {
        Counts subtract(Counts other) {
            return new Counts(completed - other.completed, bytes - other.bytes, expectedAborts - other.expectedAborts,
                    completedHits - other.completedHits, completedMisses - other.completedMisses,
                    abortedHits - other.abortedHits, abortedMisses - other.abortedMisses, slowCompleted - other.slowCompleted);
        }
    }
    private static final class Counters {
        final LongAdder completed = new LongAdder(), bytes = new LongAdder(), expectedAborts = new LongAdder();
        final LongAdder completedHits = new LongAdder(), completedMisses = new LongAdder(), abortedHits = new LongAdder();
        final LongAdder abortedMisses = new LongAdder(), slowCompleted = new LongAdder();
        Counts snapshot() { return new Counts(completed.sum(), bytes.sum(), expectedAborts.sum(), completedHits.sum(),
                completedMisses.sum(), abortedHits.sum(), abortedMisses.sum(), slowCompleted.sum()); }
    }

    private static final class GcMonitor implements AutoCloseable {
        final AtomicReference<GcSnapshot> latest = new AtomicReference<>();
        final AtomicLong events = new AtomicLong(), measuredEvents = new AtomicLong(), maxMeasuredGcMillis = new AtomicLong();
        final List<NotificationEmitter> emitters = new ArrayList<>();
        final Set<String> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP).map(p -> p.getName()).collect(Collectors.toSet());
        volatile boolean measuring;
        final NotificationListener listener = (notification, ignored) -> {
            if (!notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) return;
            GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            long heap = info.getGcInfo().getMemoryUsageAfterGc().entrySet().stream()
                    .filter(entry -> heapPools.contains(entry.getKey())).mapToLong(entry -> entry.getValue().getUsed()).sum();
            boolean measured = measuring;
            latest.set(new GcSnapshot(events.incrementAndGet(), heap, measured));
            if (measured) {
                measuredEvents.incrementAndGet();
                maxMeasuredGcMillis.accumulateAndGet(info.getGcInfo().getDuration(), Math::max);
            }
        };
        GcMonitor() {
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (bean instanceof NotificationEmitter emitter) {
                    emitter.addNotificationListener(listener, null, null); emitters.add(emitter);
                }
            }
        }
        @Override public void close() {
            for (NotificationEmitter emitter : emitters) {
                try { emitter.removeNotificationListener(listener); }
                catch (javax.management.ListenerNotFoundException ignored) { }
            }
        }
    }

    private record Settings(int warmupSeconds, int measurementSeconds, int concurrency, Path outputDirectory,
                            String sourceCommit) {
        static Settings parse(String[] args) {
            if (args.length > 5) throw new IllegalArgumentException("Usage: CacheSoakRunner [warmupSeconds measurementSeconds concurrency outputDir sourceCommit]");
            int warmup = args.length > 0 ? Integer.parseInt(args[0]) : 30;
            int measured = args.length > 1 ? Integer.parseInt(args[1]) : 600;
            int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 20;
            if (warmup < 0 || warmup > 3600 || measured < 5 || measured > 86400 || concurrency < 6 || concurrency > 128)
                throw new IllegalArgumentException("Require warmup0..3600s, measurement5..86400s and concurrency6..128");
            return new Settings(warmup, measured, concurrency,
                    Path.of(args.length > 3 ? args[3] : "build/cache-soak").toAbsolutePath(), args.length > 4 ? args[4] : "unknown");
        }
        int slowReaders() { return Math.max(1, concurrency / 5); }
        int abortReaders() { return Math.max(1, concurrency / 10); }
        int fastReaders() { return concurrency - slowReaders() - abortReaders(); }
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .map(entry -> quote(String.valueOf(entry.getKey())) + ":" + json(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        if (value instanceof Iterable<?> values) {
            List<String> parts = new ArrayList<>();
            for (Object item : values) parts.add(json(item));
            return String.join(",", parts).transform(text -> "[" + text + "]");
        }
        return quote(value.toString());
    }

    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> { if (c < 32) result.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else result.append(c); }
            }
        }
        return result.append('"').toString();
    }
}
