package dev.example.jettyclient;

import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.ProxyRequestPlan;
import dev.example.jettyclient.proxy.ProxyResponseBody;
import dev.example.jettyclient.proxy.ProxyResponseBodyPolicy;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises optional full-body rewriting through a real application Filter and both HTTP sockets. */
@Timeout(20)
@Execution(ExecutionMode.SAME_THREAD)
class JettyProxyRewriteIntegrationTest {
    private static final byte[] ORIGINAL = ascii("before old-link after");
    private static final byte[] REWRITTEN = ascii("before new after");
    private static final byte[] BINARY = {0, 1, 2, (byte)255};
    private static final AtomicInteger POLICY_CALLS = new AtomicInteger();
    private static final AtomicInteger REWRITE_CALLS = new AtomicInteger();
    private static final AtomicReference<byte[]> HELD_INPUT = new AtomicReference<>();
    private static volatile CountDownLatch rewriteEntered = new CountDownLatch(0);
    private static volatile CountDownLatch releaseRewrite = new CountDownLatch(0);
    private static volatile CountDownLatch releaseStream = new CountDownLatch(0);
    private static RawUpstream upstream;
    private static ConfigurableApplicationContext application;
    private static JettyProxyEngine engine;
    private static HttpClient client;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        upstream = new RawUpstream();
        application = new SpringApplicationBuilder(JettyClientExampleApplication.class, AdapterBeans.class)
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--example.proxy.max-concurrent=1", "--example.proxy.exchange-timeout=1500ms",
                        "--example.proxy.idle-timeout=10s");
        Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
        port = (int)webServer.getClass().getMethod("getPort").invoke(webServer);
        engine = application.getBean(JettyProxyEngine.class);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    @BeforeEach
    void resetObservations() {
        POLICY_CALLS.set(0);
        REWRITE_CALLS.set(0);
        HELD_INPUT.set(null);
        rewriteEntered = new CountDownLatch(0);
        releaseRewrite = new CountDownLatch(0);
        releaseStream = new CountDownLatch(0);
    }

    @AfterEach
    void releasesAdmission() {
        releaseRewrite.countDown();
        releaseStream.countDown();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(0, engine.activeRequests()));
        assertNull(upstream.failure.get(), "The independent upstream fixture must complete correctly");
    }

    @AfterAll
    static void stop() throws Exception {
        releaseRewrite.countDown();
        releaseStream.countDown();
        if (application != null) application.close();
        if (client != null) client.close();
        if (upstream != null) upstream.close();
    }

    @Test
    void rewritesAcrossUpstreamChunksWithBorrowedSpansAndCorrectRepresentationHeaders() throws Exception {
        HttpResponse<byte[]> response = get("rewrite");

        assertEquals(200, response.statusCode());
        assertArrayEquals(REWRITTEN, response.body());
        assertEquals(Integer.toString(REWRITTEN.length), response.headers().firstValue("Content-Length").orElseThrow());
        for (String stale : List.of("ETag", "Content-MD5", "Digest", "Content-Digest", "Repr-Digest", "Accept-Ranges"))
            assertTrue(response.headers().allValues(stale).isEmpty(), "Stale representation header: " + stale);
        assertEquals(List.of("first=1; Path=/", "second=2; Path=/"), response.headers().allValues("Set-Cookie"));
        assertEquals("retained", response.headers().firstValue("X-Upstream").orElseThrow());
        assertEquals(1, REWRITE_CALLS.get());
    }

    @Test
    void streamsCompressedBinaryBodylessAndPartialResponsesWithoutRewriting() throws Exception {
        HttpResponse<byte[]> gzip = get("gzip");
        assertEquals(200, gzip.statusCode());
        assertArrayEquals(upstream.gzip, gzip.body());
        assertEquals("gzip", gzip.headers().firstValue("Content-Encoding").orElseThrow());
        assertEquals(0, POLICY_CALLS.get(), "Encoded bytes must bypass the rewrite selector");
        awaitReleased();

        for (String route : List.of("head", "empty", "not-modified", "partial")) {
            HttpRequest request = request(route).method(route.equals("head") ? "HEAD" : "GET",
                    HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int expectedStatus = switch (route) {
                case "empty" -> 204;
                case "not-modified" -> 304;
                case "partial" -> 206;
                default -> 200;
            };
            assertEquals(expectedStatus, response.statusCode());
            assertArrayEquals(route.equals("partial") ? ascii("before") : new byte[0], response.body());
            assertEquals(0, POLICY_CALLS.get(), "Bodyless and partial responses must bypass the selector");
            awaitReleased();
        }

        HttpResponse<byte[]> binary = get("binary");
        assertArrayEquals(BINARY, binary.body());
        assertEquals(1, POLICY_CALLS.get(), "The application policy chooses streaming for binary metadata");
        assertEquals(0, REWRITE_CALLS.get());
    }

    @Test
    void requestAndResponseNoTransformDirectivesBypassTheRewriteSelector() throws Exception {
        HttpResponse<byte[]> requested = client.send(request("rewrite")
                        .header("Cache-Control", "max-age=0")
                        .header("Cache-Control", "No-TrAnSfOrM, no-cache").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, requested.statusCode());
        assertArrayEquals(ORIGINAL, requested.body());
        assertEquals("\"source\"", requested.headers().firstValue("ETag").orElseThrow());
        assertEquals(0, POLICY_CALLS.get(), "The original request forbids intermediary transformations");
        awaitReleased();

        HttpResponse<byte[]> declared = get("no-transform");
        assertEquals(200, declared.statusCode());
        assertArrayEquals(ORIGINAL, declared.body());
        assertEquals("public, No-TrAnSfOrM, max-age=60", declared.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(0, POLICY_CALLS.get(), "The upstream response forbids intermediary transformations");
        assertEquals(0, REWRITE_CALLS.get());
    }

    @Test
    void rejectsDeclaredInputAboveLimitBeforeCommittingUpstreamHeaders() throws Exception {
        assertRejectedBeforeCommit("declared-limit", 502);
        assertEquals(0, REWRITE_CALLS.get());
    }

    @Test
    void rejectsChunkedInputAboveActualLimitBeforeCommittingUpstreamHeaders() throws Exception {
        assertRejectedBeforeCommit("actual-limit", 502);
        assertEquals(0, REWRITE_CALLS.get());
    }

    @Test
    void rejectsRewriteOutputAboveLimitBeforeCommittingUpstreamHeaders() throws Exception {
        assertRejectedBeforeCommit("output-limit", 502);
        assertEquals(1, REWRITE_CALLS.get());
    }

    @Test
    void reportsApplicationRewriteFailureBeforeCommittingUpstreamHeaders() throws Exception {
        assertRejectedBeforeCommit("rewrite-failure", 500);
        assertEquals(1, REWRITE_CALLS.get());
        awaitReleased();
        assertArrayEquals(BINARY, get("binary").body(), "A failed rewriter must not strand admission");
    }

    @Test
    void timeoutKeepsWorkerInputAndAdmissionUntilTheRewriterActuallyReturns() throws Exception {
        rewriteEntered = new CountDownLatch(1);
        releaseRewrite = new CountDownLatch(1);
        try {
            var response = client.sendAsync(request("blocked-rewrite").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertTrue(rewriteEntered.await(3, TimeUnit.SECONDS), "The worker must hold the borrowed input");
            assertEquals(504, response.get(4, TimeUnit.SECONDS).statusCode());
            assertEquals(1, engine.activeRequests(), "A timeout cannot release memory still read by the worker");
            assertEquals(503, get("binary").statusCode(), "Admission remains occupied until worker completion");
            releaseRewrite.countDown();
            awaitReleased();
            assertArrayEquals(ORIGINAL, HELD_INPUT.get(), "The worker must retain valid bytes after timeout");
            assertArrayEquals(BINARY, get("binary").body(), "Late rewrite output is discarded and admission recovers");
        } finally {
            releaseRewrite.countDown();
        }
    }

    @Test
    void unselectedStreamPublishesFirstBytesBeforeTheUpstreamHasFinished() throws Exception {
        releaseStream = new CountDownLatch(1);
        try {
            HttpResponse<InputStream> response = client.send(request("stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertArrayEquals(ascii("first"), body.readNBytes(5));
                assertEquals(1, releaseStream.getCount(), "The upstream EOF must still be gated");
                releaseStream.countDown();
                assertArrayEquals(ascii("-last"), body.readAllBytes());
            }
            assertEquals(0, REWRITE_CALLS.get());
        } finally {
            releaseStream.countDown();
        }
    }

    private static void assertRejectedBeforeCommit(String route, int status) throws Exception {
        HttpResponse<byte[]> response = get(route);
        assertEquals(status, response.statusCode());
        assertEquals(0, response.body().length);
        assertTrue(response.headers().allValues("X-Upstream").isEmpty(), "Buffered headers must not leak on failure");
    }

    private static HttpResponse<byte[]> get(String route) throws Exception {
        return client.send(request(route).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpRequest.Builder request(String route) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/adapted/" + route))
                .timeout(Duration.ofSeconds(6));
    }

    private static void awaitReleased() {
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertEquals(0, engine.activeRequests()));
    }

    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }

    private static byte[] bytes(ProxyResponseBody body) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (ByteBuffer view : body.buffers()) while (view.hasRemaining()) output.write(view.get());
        return output.toByteArray();
    }

    private static List<ByteBuffer> slice(ProxyResponseBody body, long from, long length) {
        List<ByteBuffer> result = new ArrayList<>();
        long skip = from;
        for (ByteBuffer view : body.buffers()) {
            if (skip >= view.remaining()) { skip -= view.remaining(); continue; }
            view.position(view.position() + (int)skip);
            skip = 0;
            int count = (int)Math.min(length, view.remaining());
            view.limit(view.position() + count);
            result.add(view.slice());
            length -= count;
            if (length == 0) return result;
        }
        assertEquals(0, length, "Requested borrowed span must exist in the complete input");
        return result;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdapterBeans {
        @Bean
        FilterRegistrationBean<Filter> applicationProxyAdapter(JettyProxyEngine engine) {
            Filter adapter = (request, response, chain) -> {
                HttpServletRequest incoming = (HttpServletRequest)request;
                String route = incoming.getRequestURI().substring("/adapted".length());
                URI target = URI.create("http://127.0.0.1:" + upstream.port() + route);
                ProxyResponseBodyPolicy policy = (metadata, head) -> {
                    POLICY_CALLS.incrementAndGet();
                    if (!head.headerValues("Content-Type").contains("text/html")) return null;
                    return new ProxyResponseBodyPolicy.Selection(64, 64, (ignored, input) -> {
                        REWRITE_CALLS.incrementAndGet();
                        if (route.equals("/rewrite-failure")) throw new IllegalStateException("Application rewrite failed");
                        if (route.equals("/output-limit")) return ProxyResponseBody.of(ByteBuffer.wrap(new byte[65]));
                        if (route.equals("/blocked-rewrite")) {
                            rewriteEntered.countDown();
                            if (!releaseRewrite.await(10, TimeUnit.SECONDS)) throw new IOException("Rewrite fixture gate timed out");
                            HELD_INPUT.set(bytes(input));
                        }
                        assertArrayEquals(ORIGINAL, bytes(input));
                        List<ByteBuffer> spans = new ArrayList<>(slice(input, 0, 7));
                        spans.add(ByteBuffer.wrap(ascii("new")));
                        spans.addAll(slice(input, 15, input.length() - 15));
                        return ProxyResponseBody.of(spans);
                    });
                };
                engine.forward(incoming, (HttpServletResponse)response,
                        ProxyRequestPlan.builder(target).responseBody(policy).build());
            };
            var registration = new FilterRegistrationBean<>(adapter);
            registration.setName("rewriteApplicationAdapter");
            registration.addUrlPatterns("/adapted/*");
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setAsyncSupported(true);
            return registration;
        }
    }

    private static final class RawUpstream implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket();
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final byte[] gzip;

        RawUpstream() throws IOException {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream output = new GZIPOutputStream(compressed)) { output.write(ORIGINAL); }
            gzip = compressed.toByteArray();
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            workers.execute(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket socket = listener.accept();
                        sockets.add(socket);
                        workers.execute(() -> serve(socket));
                    } catch (IOException closing) {
                        if (!listener.isClosed()) failure.compareAndSet(null, closing);
                    }
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        private void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(5000);
                InputStream input = new BufferedInputStream(socket.getInputStream());
                String requestLine = line(input);
                if (requestLine == null) return;
                String route = requestLine.split(" ", 3)[1];
                for (String header; (header = line(input)) != null && !header.isEmpty(); ) { }
                switch (route) {
                    case "/gzip" -> fixed(socket, 200, "Content-Type: text/html\r\nContent-Encoding: gzip\r\n", gzip, false);
                    case "/binary" -> fixed(socket, 200, "Content-Type: application/octet-stream\r\n", BINARY, false);
                    case "/no-transform" -> fixed(socket, 200, "Content-Type: text/html\r\n"
                            + "Cache-Control: public, No-TrAnSfOrM, max-age=60\r\n", ORIGINAL, false);
                    case "/head" -> fixed(socket, 200, "Content-Type: text/html\r\n", ORIGINAL, true);
                    case "/empty" -> fixed(socket, 204, "Content-Type: text/html\r\n", new byte[0], true);
                    case "/not-modified" -> fixed(socket, 304, "Content-Type: text/html\r\nETag: \"source\"\r\n", ORIGINAL, true);
                    case "/partial" -> fixed(socket, 206, "Content-Type: text/html\r\nContent-Range: bytes 0-5/21\r\n", ascii("before"), false);
                    case "/declared-limit" -> fixed(socket, 200, "Content-Type: text/html\r\nX-Upstream: retained\r\n", new byte[65], false);
                    case "/actual-limit" -> chunked(socket, List.of(new byte[32], new byte[33]));
                    case "/stream" -> {
                        send(socket, "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                                + "Content-Length: 10\r\nConnection: close\r\n\r\nfirst");
                        if (!releaseStream.await(8, TimeUnit.SECONDS)) throw new IOException("Stream fixture gate timed out");
                        send(socket, "-last");
                    }
                    default -> chunked(socket, List.of(ascii("before old-"), ascii("link after")));
                }
            } catch (IOException cancelledPeer) {
                // Input/output limits and cancellation deliberately close the upstream socket.
            } catch (Throwable unexpected) {
                failure.compareAndSet(null, unexpected);
            } finally {
                sockets.remove(socket);
            }
        }

        private static void fixed(Socket socket, int status, String headers, byte[] body, boolean noBody)
                throws IOException {
            send(socket, "HTTP/1.1 " + status + " Fixture\r\n" + headers
                    + (status == 204 ? "" : "Content-Length: " + body.length + "\r\n")
                    + "Connection: close\r\n\r\n");
            if (!noBody) { socket.getOutputStream().write(body); socket.getOutputStream().flush(); }
        }

        private static void chunked(Socket socket, List<byte[]> chunks) throws IOException {
            send(socket, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nTransfer-Encoding: chunked\r\n"
                    + "ETag: \"source\"\r\nContent-MD5: stale\r\nDigest: SHA-256=stale\r\n"
                    + "Content-Digest: sha-256=:c3RhbGU=:\r\nRepr-Digest: sha-256=:c3RhbGU=:\r\n"
                    + "Accept-Ranges: bytes\r\nX-Upstream: retained\r\n"
                    + "Set-Cookie: first=1; Path=/\r\nSet-Cookie: second=2; Path=/\r\nConnection: close\r\n\r\n");
            for (byte[] chunk : chunks) {
                send(socket, Integer.toHexString(chunk.length) + "\r\n");
                socket.getOutputStream().write(chunk);
                send(socket, "\r\n");
            }
            send(socket, "0\r\n\r\n");
        }

        private static void send(Socket socket, String wire) throws IOException {
            socket.getOutputStream().write(ascii(wire));
            socket.getOutputStream().flush();
        }

        private static String line(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int value; (value = input.read()) >= 0; ) {
                if (value == '\n') return bytes.toString(StandardCharsets.ISO_8859_1).stripTrailing();
                bytes.write(value);
                if (bytes.size() > 16_384) throw new IOException("Fixture line too long");
            }
            return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.ISO_8859_1);
        }

        @Override public void close() throws IOException {
            listener.close();
            for (Socket socket : sockets) socket.close();
            workers.shutdownNow();
        }
    }
}
