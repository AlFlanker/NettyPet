package dev.example.integrationhost;

import com.otr.portal.proxy.caching.CachePublicationQueue;
import com.otr.portal.proxy.caching.CachedResponse;
import com.otr.portal.proxy.caching.CachedResponseWriter;
import com.otr.portal.proxy.caching.ContentCachingResponse;
import com.otr.portal.proxy.caching.SharedResponseCache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.jettyclient.proxy.JettyProxyClientFactory;
import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.JettyProxyProperties;
import dev.example.jettyclient.proxy.ProxyCall;
import dev.example.jettyclient.proxy.ProxyRequestPlan;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * Complete application cache pipeline shared by HTTP regression tests and the isolated soak JVM.
 * Counters have constant space: neither raw keys, requests nor completed calls are retained here.
 */
public final class CacheIntegrationServer implements AutoCloseable {
    public static final int BODY_BYTES = 8 * 1024 * 1024;
    public static final int SMALL_BODY_BYTES = 64 * 1024;
    public static final int ORIGIN_CHUNK_BYTES = 64 * 1024;
    public static final int ORIGIN_WORKERS = 32;
    private static final byte[] BODY = payload(BODY_BYTES);
    private static final String EXPECTED_SHA256 = sha256(BODY);
    private static final Set<String> HIT_OMITTED_HEADERS = Set.of(
            "x-cache", "content-length", "connection", "transfer-encoding", "keep-alive", "trailer");

    private final ContentCachingResponse.CaptureBudget captureBudget;
    private final SharedResponseCache cache;
    private final CachePublicationQueue publications;
    private final int maxEntryBytes;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong upstreamRequests = new AtomicLong();
    private final AtomicLong stored = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong publicationFailures = new AtomicLong();
    private final AtomicLong publicationBypassed = new AtomicLong();
    private final AtomicLong pendingPublicationCallbacks = new AtomicLong();
    private final AtomicLong activeHits = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    // JDK 21 HttpServer writes under a monitor. Blocking origin writes must not pin
    // the shared virtual-thread carriers used by the load generator's readers.
    private final ExecutorService upstreamWorkers = new ThreadPoolExecutor(ORIGIN_WORKERS, ORIGIN_WORKERS,
            0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
            Thread.ofPlatform().name("cache-origin-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final HttpServer upstream;
    private final org.eclipse.jetty.client.HttpClient transport;
    private final JettyProxyEngine engine;
    private final Server server = new Server(new QueuedThreadPool(32, 8));
    private final ServerConnector connector = new ServerConnector(server, 1, 1);
    private final URI baseUri;

    public CacheIntegrationServer() throws Exception {
        this(48L * 1024 * 1024, 64L * 1024 * 1024, BODY_BYTES, Clock.systemUTC());
    }

    public CacheIntegrationServer(long maxStoreWeight, long maxCaptureBytes, int maxEntryBytes, Clock clock)
            throws Exception {
        this.maxEntryBytes = maxEntryBytes;
        captureBudget = new ContentCachingResponse.CaptureBudget(maxCaptureBytes);
        cache = new SharedResponseCache(maxStoreWeight, maxEntryBytes, clock);
        publications = new CachePublicationQueue(cache, 1, 4);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(upstreamWorkers);
        upstream.createContext("/", this::respondFromOrigin);
        upstream.start();
        JettyProxyProperties properties = JettyProxyProperties.defaults();
        transport = JettyProxyClientFactory.createStarted(properties);
        engine = new JettyProxyEngine(transport, properties);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        connector.setAcceptedSendBufferSize(64 * 1024);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder servlet = new ServletHolder(new HttpServlet() {
            @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                if (request.getRequestURI().equals("/health")) {
                    response.getWriter().write("ok");
                    return;
                }
                serve(request, response);
            }
        });
        servlet.setAsyncSupported(true);
        context.addServlet(servlet, "/*");
        server.setHandler(context);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + connector.getLocalPort());
    }

    public URI baseUri() { return baseUri; }
    public static String expectedSha256() { return EXPECTED_SHA256; }

    public record Stats(long hits, long misses, long upstreamRequests, long stored, long rejected,
                        long publicationFailures, long activeHits, int engineActive, long captureReserved,
                        long cacheWeight, long cacheEntries, int publicationActive, int publicationQueued,
                        long evictions, long publicationBypassed, int publicationPending,
                        long pendingPublicationCallbacks) { }

    public Stats stats() {
        return new Stats(hits.get(), misses.get(), upstreamRequests.get(), stored.get(), rejected.get(),
                publicationFailures.get(), activeHits.get(), engine.activeRequests(), captureBudget.reservedBytes(),
                cache.estimatedWeight(), cache.size(), publications.activeTasks(), publications.queuedTasks(),
                cache.evictionCount(), publicationBypassed.get(), publications.pendingTasks(),
                pendingPublicationCallbacks.get());
    }

    public boolean isCached(String rawPathAndQuery) {
        URI target = URI.create(rawPathAndQuery);
        return cache.get(SharedResponseCache.key("fixture", target.getRawPath(), target.getRawQuery())) != null;
    }

    public void clearCache() { cache.clear(); }

    public void awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (engine.activeRequests() == 0 && activeHits.get() == 0
                    && publications.pendingTasks() == 0 && captureBudget.reservedBytes() == 0
                    && pendingPublicationCallbacks.get() == 0) return;
            TimeUnit.MILLISECONDS.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Cache fixture failed to quiesce: " + stats());
    }

    private void serve(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        String rawTarget = path + (query == null ? "" : "?" + query);
        String key = SharedResponseCache.key("fixture", path, query);
        boolean eligible = SharedResponseCache.eligibleRequest(request);
        CachedResponse cached = eligible ? cache.get(key) : null;
        if (cached != null) {
            hits.incrementAndGet();
            activeHits.incrementAndGet();
            AtomicBoolean terminal = new AtomicBoolean();
            Runnable completed = () -> {
                if (terminal.compareAndSet(false, true)) activeHits.decrementAndGet();
            };
            try {
                response.setStatus(cached.getStatus());
                for (Map.Entry<String, String> header : cached.getHeaders().entries()) {
                    if (!HIT_OMITTED_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT)))
                        response.addHeader(header.getKey(), header.getValue());
                }
                response.setHeader("X-Cache", "HIT");
                response.setContentLength(cached.getContent().length);
                var async = request.startAsync(request, response);
                async.setTimeout(20_000);
                CachedResponseWriter.write(async, response, cached.getContent(), completed);
            } catch (IOException | RuntimeException | Error failure) {
                completed.run();
                throw failure;
            }
            return;
        }
        misses.incrementAndGet();
        response.setHeader("X-Cache", eligible ? "MISS" : "BYPASS");
        URI target = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + rawTarget);
        ProxyRequestPlan plan = ProxyRequestPlan.builder(target).timeout(Duration.ofSeconds(20)).build();
        if (!eligible) {
            engine.forward(request, response, plan);
            return;
        }
        ContentCachingResponse capture = new ContentCachingResponse(response, maxEntryBytes, captureBudget);
        // The engine releases its active count before invoking completion observers.
        // Register first so awaitIdle also covers that gap for captures that bypass storage.
        pendingPublicationCallbacks.incrementAndGet();
        ProxyCall call;
        try {
            call = engine.forward(request, capture, plan);
        } catch (RuntimeException | Error failure) {
            try { capture.discardCapture(); }
            finally { pendingPublicationCallbacks.decrementAndGet(); }
            throw failure;
        }
        call.completion().whenComplete((result, failure) -> {
            try {
                boolean successful = failure == null && result.failure() == null && result.status() == 200;
                publications.publish(key, capture, successful).whenComplete((publication, publicationFailure) -> {
                    try {
                        if (publicationFailure != null) publicationFailures.incrementAndGet();
                        else switch (publication) {
                            case STORED -> stored.incrementAndGet();
                            case REJECTED, CLOSED -> rejected.incrementAndGet();
                            case BYPASSED -> publicationBypassed.incrementAndGet();
                        }
                    } finally { pendingPublicationCallbacks.decrementAndGet(); }
                });
            } catch (RuntimeException | Error unexpected) {
                try { capture.discardCapture(); }
                finally {
                    publicationFailures.incrementAndGet();
                    pendingPublicationCallbacks.decrementAndGet();
                }
                throw unexpected;
            }
        });
    }

    private void respondFromOrigin(HttpExchange exchange) throws IOException {
        upstreamRequests.incrementAndGet();
        String path = exchange.getRequestURI().getRawPath();
        try {
            int length = path.startsWith("/small") ? SMALL_BODY_BYTES : BODY.length;
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("X-Origin-Key", exchange.getRequestURI().toASCIIString());
            exchange.getResponseHeaders().set("Cache-Control", path.startsWith("/no-store")
                    ? "no-store" : path.startsWith("/short-lived") ? "public, max-age=60" : "public, max-age=3600");
            exchange.sendResponseHeaders(200, length);
            int delivered = path.startsWith("/truncated") ? SMALL_BODY_BYTES : length;
            // HttpServer's WriteStream grows a per-connection heap buffer to twice
            // one write's size. Chunking avoids an unrelated 16 MiB allocation for
            // every 8 MiB origin response while preserving the identical body.
            // Let write/close failures reach HttpServer's dispatcher: it removes failed
            // response connections from its registry. Swallowing IOException here would
            // retain their per-connection buffers after downstream cancellation on JDK 21.
            try (var output = exchange.getResponseBody()) {
                for (int offset = 0; offset < delivered; offset += ORIGIN_CHUNK_BYTES)
                    output.write(BODY, offset, Math.min(ORIGIN_CHUNK_BYTES, delivered - offset));
            }
        } finally {
            exchange.close();
        }
    }

    @Override public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        Throwable failure = null;
        Cleanup[] cleanup = {engine::close, server::stop, transport::stop, () -> upstream.stop(0),
                () -> {
                    upstreamWorkers.shutdownNow();
                    if (!upstreamWorkers.awaitTermination(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("Origin workers did not stop");
                }, publications::close,
                () -> {
                    if (!publications.awaitTermination(Duration.ofSeconds(10)))
                        throw new IllegalStateException("Cache publication worker did not stop");
                }, cache::clear, () -> awaitIdle(Duration.ofSeconds(5))};
        for (Cleanup action : cleanup) {
            try { action.run(); }
            catch (Exception | Error next) {
                if (failure == null) failure = next;
                else if (next != failure) failure.addSuppressed(next);
            }
        }
        if (failure instanceof Error fatal) throw fatal;
        if (failure instanceof Exception exception) throw exception;
    }

    @FunctionalInterface private interface Cleanup { void run() throws Exception; }

    private static byte[] payload(int length) {
        byte[] result = new byte[length];
        new Random(4815162342L).nextBytes(result);
        return result;
    }

    private static String sha256(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (NoSuchAlgorithmException impossible) { throw new ExceptionInInitializerError(impossible); }
    }
}
