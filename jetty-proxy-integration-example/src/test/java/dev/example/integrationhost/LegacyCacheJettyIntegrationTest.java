package dev.example.integrationhost;

import com.otr.portal.proxy.caching.CachedResponse;
import com.otr.portal.proxy.caching.CachedResponseWriter;
import com.otr.portal.proxy.caching.ContentCachingResponse;
import com.sun.net.httpserver.HttpServer;
import dev.example.jettyclient.proxy.JettyProxyClientFactory;
import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.JettyProxyProperties;
import dev.example.jettyclient.proxy.ProxyCall;
import dev.example.jettyclient.proxy.ProxyExchangeResult;
import dev.example.jettyclient.proxy.ProxyRequestPlan;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual portal cache wrapper against the library and a real EE11 container. */
@Timeout(30)
class LegacyCacheJettyIntegrationTest {
    private static final int MIB = 1024 * 1024;
    private static final byte[] LARGE = bytes(8 * MIB);
    private static final byte[] SMALL = bytes(64 * 1024);

    @Test
    void slowEightMebibyteCacheHitAlsoUsesNonblockingWrites() throws Exception {
        try (Fixture fixture = new Fixture(8 * MIB, 16L * MIB)) {
            HttpResponse<InputStream> response = fixture.open("/hit");
            try (InputStream body = response.body()) {
                Probe probe = fixture.probe("/hit");
                assertTrue(probe.notReady.await(5, TimeUnit.SECONDS));
                assertFalse(fixture.hitFinished.isDone());
                assertEquals("ok", fixture.text("/health"));
                assertDigest(LARGE, body.readAllBytes());
                fixture.hitFinished.get(5, TimeUnit.SECONDS);
                assertEquals(0, fixture.budget.reservedBytes(), "A hit does not allocate another capture");
            }
        }
    }

    @Test
    void slowEightMebibyteMissUsesRealReadinessAndPublishesOnlyTheCompleteBody() throws Exception {
        try (Fixture fixture = new Fixture(8 * MIB, 16L * MIB)) {
            HttpResponse<InputStream> response = fixture.open("/large");
            try (InputStream body = response.body()) {
                Probe probe = fixture.probe("/large");
                assertTrue(probe.notReady.await(5, TimeUnit.SECONDS), "The real socket must exert backpressure");
                assertFalse(probe.completed.isDone(), "A paused download cannot publish a cache entry");
                assertEquals(16L * MIB, fixture.budget.reservedBytes());
                assertTrue(probe.capture.getContentSize() <= LARGE.length);
                assertEquals("ok", fixture.text("/health"), "A slow cache miss must not block other requests");
                assertDigest(LARGE, body.readAllBytes());
                assertTrue(probe.completed.get(10, TimeUnit.SECONDS).success());
                assertNotNull(probe.cached);
                assertDigest(LARGE, probe.cached.getContent());
                assertEquals(0, fixture.budget.reservedBytes());
                assertEquals(0, fixture.engine.activeRequests());
            }
        }
    }

    @Test
    void declaredAndChunkedOversizeResponsesBypassCaptureWithoutTruncatingTheClientBody() throws Exception {
        try (Fixture fixture = new Fixture(64 * 1024, 256 * 1024)) {
            for (String path : new String[]{"/large", "/chunked"}) {
                HttpResponse<byte[]> response = fixture.client.send(fixture.request(path), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, response.statusCode());
                assertDigest(LARGE, response.body());
                Probe probe = fixture.probe(path);
                assertTrue(probe.completed.get(10, TimeUnit.SECONDS).success());
                assertNull(probe.cached, "Oversize responses must never leave a partial cache entry");
                assertEquals(0, probe.capture.getContentSize());
                assertEquals(0, fixture.budget.reservedBytes());
            }
        }
    }

    @Test
    void disconnectedSlowReaderCannotPublishAPartialEntryOrRetainTheCaptureBudget() throws Exception {
        try (Fixture fixture = new Fixture(8 * MIB, 16L * MIB)) {
            HttpResponse<InputStream> response = fixture.open("/large");
            Probe probe = fixture.probe("/large");
            try (InputStream ignored = response.body()) {
                assertTrue(probe.notReady.await(5, TimeUnit.SECONDS));
                assertFalse(probe.completed.isDone());
            }
            ProxyExchangeResult result = probe.completed.get(10, TimeUnit.SECONDS);
            assertFalse(result.success());
            assertNull(probe.cached);
            assertEquals(0, probe.capture.getContentSize());
            assertEquals(0, fixture.budget.reservedBytes());
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void exhaustedCaptureBudgetBypassesAnotherRequestInsteadOfRejectingOrQueuingIt() throws Exception {
        try (Fixture fixture = new Fixture(8 * MIB, 16L * MIB)) {
            HttpResponse<InputStream> slow = fixture.open("/large");
            try (InputStream body = slow.body()) {
                Probe first = fixture.probe("/large");
                assertTrue(first.notReady.await(5, TimeUnit.SECONDS));
                assertEquals(16L * MIB, fixture.budget.reservedBytes());
                HttpResponse<byte[]> next = fixture.client.send(fixture.request("/small"), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, next.statusCode());
                assertDigest(SMALL, next.body());
                Probe second = fixture.probe("/small");
                assertTrue(second.completed.get(5, TimeUnit.SECONDS).success());
                assertNull(second.cached);
                assertEquals(16L * MIB, fixture.budget.reservedBytes(), "The original capture still owns its reservation");
                assertDigest(LARGE, body.readAllBytes());
                assertTrue(first.completed.get(10, TimeUnit.SECONDS).success());
                assertNotNull(first.cached);
                assertEquals(0, fixture.budget.reservedBytes());
            }
        }
    }

    private static byte[] bytes(int length) {
        byte[] result = new byte[length];
        new Random(8923).nextBytes(result);
        return result;
    }

    private static void assertDigest(byte[] expected, byte[] actual) throws Exception {
        assertEquals(expected.length, actual.length);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), MessageDigest.getInstance("SHA-256").digest(actual));
    }

    private static final class Probe {
        final CountDownLatch notReady = new CountDownLatch(1);
        final CompletableFuture<ProxyExchangeResult> completed = new CompletableFuture<>();
        volatile ContentCachingResponse capture;
        volatile ProxyCall call;
        volatile CachedResponse cached;
    }

    private static final class Fixture implements AutoCloseable {
        final ContentCachingResponse.CaptureBudget budget;
        final Map<String, Probe> probes = new ConcurrentHashMap<>();
        final CompletableFuture<Void> hitFinished = new CompletableFuture<>();
        final ExecutorService upstreamWorkers = Executors.newVirtualThreadPerTaskExecutor();
        final ThreadPoolExecutor publications = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4), Thread.ofPlatform().name("cache-publication-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        final HttpServer upstream;
        final org.eclipse.jetty.client.HttpClient transport;
        final JettyProxyEngine engine;
        final Server server = new Server();
        final ServerConnector connector = new ServerConnector(server, 1, 1);
        final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();

        Fixture(int maxEntry, long maxCapture) throws Exception {
            budget = new ContentCachingResponse.CaptureBudget(maxCapture);
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.setExecutor(upstreamWorkers);
            upstream.createContext("/", exchange -> {
                try {
                    byte[] body = exchange.getRequestURI().getPath().equals("/small") ? SMALL : LARGE;
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, exchange.getRequestURI().getPath().equals("/chunked") ? 0 : body.length);
                    exchange.getResponseBody().write(body);
                } catch (IOException cancelled) {
                    // A cancelled downstream intentionally aborts this upstream exchange.
                } finally { exchange.close(); }
            });
            upstream.start();
            transport = JettyProxyClientFactory.createStarted(JettyProxyProperties.defaults());
            engine = new JettyProxyEngine(transport, JettyProxyProperties.defaults());
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            connector.setAcceptedSendBufferSize(8192);
            server.addConnector(connector);
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            ServletHolder holder = new ServletHolder(new HttpServlet() {
                @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    String path = request.getRequestURI();
                    if (path.equals("/health")) { response.getWriter().write("ok"); return; }
                    Probe probe = new Probe();
                    probes.put(path, probe);
                    if (path.equals("/hit")) {
                        HttpServletResponse observed = observeReadiness(response, probe);
                        observed.setContentLength(LARGE.length);
                        var async = request.startAsync(request, observed);
                        async.setTimeout(20_000);
                        CachedResponseWriter.write(async, observed, LARGE, () -> hitFinished.complete(null));
                        return;
                    }
                    probe.capture = new ContentCachingResponse(observeReadiness(response, probe), maxEntry, budget);
                    probe.call = engine.forward(request, probe.capture, ProxyRequestPlan.builder(
                            URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + path)).build());
                    probe.call.completion().whenComplete((result, failure) -> {
                        // Copying a complete cache body is application work, off the I/O owner.
                        try {
                            publications.execute(() -> {
                                try {
                                    probe.cached = probe.capture.finishCapture(failure == null
                                            && result.success() && result.status() == 200).orElse(null);
                                    if (failure == null) probe.completed.complete(result);
                                    else probe.completed.completeExceptionally(failure);
                                } catch (Throwable publicationFailure) {
                                    probe.capture.discardCapture();
                                    probe.completed.completeExceptionally(publicationFailure);
                                }
                            });
                        } catch (RuntimeException rejected) {
                            probe.capture.discardCapture();
                            probe.completed.completeExceptionally(rejected);
                        }
                    });
                }
            });
            holder.setAsyncSupported(true);
            context.addServlet(holder, "/*");
            server.setHandler(context);
            server.start();
        }

        Probe probe(String path) {
            await().atMost(Duration.ofSeconds(5)).until(() -> probes.containsKey(path));
            return probes.get(path);
        }

        HttpRequest request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + connector.getLocalPort() + path))
                    .timeout(Duration.ofSeconds(20)).GET().build();
        }

        HttpResponse<InputStream> open(String path) throws Exception {
            HttpResponse<InputStream> response = client.send(request(path), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            return response;
        }

        String text(String path) throws Exception { return client.send(request(path), HttpResponse.BodyHandlers.ofString()).body(); }

        @Override public void close() throws Exception {
            client.shutdownNow();
            for (Probe probe : probes.values()) if (probe.call != null) probe.call.cancel(new IOException("Fixture closing"));
            engine.close();
            server.stop();
            transport.stop();
            upstream.stop(0);
            upstreamWorkers.shutdownNow();
            publications.shutdown();
            assertTrue(publications.awaitTermination(5, TimeUnit.SECONDS));
            for (Probe probe : probes.values()) if (probe.capture != null) probe.capture.discardCapture();
            assertEquals(0, budget.reservedBytes());
        }
    }

    private static HttpServletResponse observeReadiness(HttpServletResponse response, Probe probe) {
        return new HttpServletResponseWrapper(response) {
            private ServletOutputStream stream;
            @Override public ServletOutputStream getOutputStream() throws IOException {
                if (stream == null) {
                    ServletOutputStream actual = super.getOutputStream();
                    stream = new ServletOutputStream() {
                        @Override public boolean isReady() {
                            boolean ready = actual.isReady();
                            if (!ready) probe.notReady.countDown();
                            return ready;
                        }
                        @Override public void setWriteListener(WriteListener listener) { actual.setWriteListener(listener); }
                        @Override public void write(int value) throws IOException { actual.write(value); }
                        @Override public void write(byte[] bytes, int offset, int length) throws IOException { actual.write(bytes, offset, length); }
                        @Override public void flush() throws IOException { actual.flush(); }
                    };
                }
                return stream;
            }
        };
    }
}
