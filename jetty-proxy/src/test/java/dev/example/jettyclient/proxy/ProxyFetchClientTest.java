package dev.example.jettyclient.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.util.thread.Invocable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises bounded fetch over actual upstream sockets with no Servlet/server proxy involved. */
@Timeout(15)
class ProxyFetchClientTest {
    private static final String TEXT = "Привет из сжатого ресурса";
    private static final byte[] PLAIN = TEXT.getBytes(StandardCharsets.UTF_8);
    private static final byte[] BINARY = new byte[256 * 1024 + 17];
    static { new Random(953).nextBytes(BINARY); }
    private HttpServer server;
    private ExecutorService serverWorkers;
    private HttpClient httpClient;
    private ProxyFetchClient fetch;
    private final CountDownLatch stalled = new CountDownLatch(1);
    private final CountDownLatch releaseStall = new CountDownLatch(1);
    private byte[] gzip;
    private byte[] deflate;

    @BeforeEach
    void start() throws Exception {
        gzip = gzip(PLAIN);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var encoder = new DeflaterOutputStream(output)) { encoder.write(PLAIN); }
        deflate = output.toByteArray();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverWorkers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverWorkers);
        server.createContext("/", this::serve);
        server.start();
        httpClient = new ProxyHttpClient(new HttpClientTransportOverHTTP(1));
        httpClient.setFollowRedirects(false);
        httpClient.setHttpCookieStore(new HttpCookieStore.Empty());
        httpClient.setMaxResponseHeadersSize(16_384);
        httpClient.start();
        httpClient.getContentDecoderFactories().clear();
        httpClient.getProtocolHandlers().clear();
        fetch = new ProxyFetchClient(httpClient, JettyProxyProperties.defaults());
    }

    @AfterEach
    void stop() throws Exception {
        releaseStall.countDown();
        if (fetch != null) {
            fetch.close();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, fetch.activeRequests()));
        }
        if (httpClient != null) httpClient.stop();
        if (server != null) server.stop(0);
        if (serverWorkers != null) serverWorkers.close();
    }

    @Test
    void usesExplicitMethodRawUriAndPreparedHeadersWithoutARequestBody() throws Exception {
        List<String> value = new ArrayList<>(List.of("captured"));
        var headers = new java.util.LinkedHashMap<String, List<String>>();
        headers.put("X-Test", value);
        headers.put("Host", List.of("forged.invalid"));
        headers.put("Connection", List.of("X-Excluded"));
        headers.put("X-Excluded", List.of("secret"));
        var request = ProxyFetchRequest.builder(uri("/inspect/a%20b?x=%2F&x=2"))
                .method("fRoB").headers(headers).build();
        value.set(0, "changed after preparation");

        ProxyFetchResult result = fetch.fetchBlocking(request);

        assertEquals(200, result.status());
        assertEquals("fRoB|/inspect/a%20b?x=%2F&x=2|captured|none|0", result.bodyAsString());
        assertNotEquals("forged.invalid", result.headerValues("X-Received-Host").getFirst());
        ByteBuffer cursor = result.body();
        assertThrows(ReadOnlyBufferException.class, () -> cursor.put(0, (byte)0));
        cursor.get();
        assertEquals(result.bodyLength(), result.body().remaining());
        assertThrows(UnsupportedOperationException.class, () -> result.headers().clear());
    }

    @Test
    void keepsCookiesSeparateAndDoesNotShareThemOrFollowRedirects() throws Exception {
        ProxyFetchResult cookies = fetch.fetchBlocking(request("/cookies"));
        assertEquals(List.of("first=1; Path=/", "second=2; Path=/"), cookies.headerValues("Set-Cookie"));
        assertEquals("none", fetch.fetchBlocking(request("/cookie-inspect")).bodyAsString());
        ProxyFetchResult explicit = fetch.fetchBlocking(ProxyFetchRequest.builder(uri("/cookie-inspect"))
                .headers(Map.of("Cookie", List.of("caller=present"))).build());
        assertEquals("caller=present", explicit.bodyAsString());
        ProxyFetchResult redirect = fetch.fetchBlocking(request("/redirect"));
        assertEquals(302, redirect.status());
        assertEquals(List.of("/cookies"), redirect.headerValues("Location"));
        assertEquals("redirect", redirect.bodyAsString());
    }

    @Test
    void preservesBinaryAndCompressedBytesByDefaultAndDecodesOnlyWhenRequested() throws Exception {
        assertArrayEquals(BINARY, bytes(fetch.fetchBlocking(request("/binary")).body()));
        ProxyFetchResult opaque = fetch.fetchBlocking(request("/gzip"));
        assertArrayEquals(gzip, bytes(opaque.body()));
        assertEquals(List.of("gzip"), opaque.headerValues("Content-Encoding"));
        for (String route : List.of("/gzip", "/deflate")) {
            ProxyFetchResult decoded = fetch.fetchBlocking(ProxyFetchRequest.builder(uri(route))
                    .decodeContentEncoding(true).build());
            assertEquals(TEXT, decoded.bodyAsString());
            assertEquals(PLAIN.length, decoded.bodyLength());
            assertTrue(decoded.headerValues("Content-Encoding").isEmpty());
            assertTrue(decoded.headerValues("ETag").isEmpty());
            assertEquals(List.of(Integer.toString(PLAIN.length)), decoded.headerValues("Content-Length"));
        }
    }

    @Test
    void appliesResponseCharsetAndAnIndependentCharacterLimit() throws Exception {
        ProxyFetchResult result = fetch.fetchBlocking(request("/charset"));
        assertEquals("Привет", result.bodyAsString(StandardCharsets.UTF_8));
        ProxyFetchResult limited = fetch.fetchBlocking(ProxyFetchRequest.builder(uri("/charset"))
                .maxTextCharacters(3).build());
        assertThrows(IOException.class, limited::bodyAsString);
    }

    @Test
    void enforcesDeclaredAndActualWireLimitsAndDecodedExpansionLimit() throws Exception {
        for (String route : List.of("/declared", "/chunked")) {
            ProxyFetchCall call = fetch.fetch(ProxyFetchRequest.builder(uri(route))
                    .maxWireBytes(32).timeout(Duration.ofSeconds(10)).build());
            assertInstanceOf(IOException.class, failure(call));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, fetch.activeRequests()));
        }
        ProxyFetchCall bomb = fetch.fetch(ProxyFetchRequest.builder(uri("/gzip-bomb"))
                .maxWireBytes(1024).maxDecodedBytes(128).decodeContentEncoding(true).build());
        assertInstanceOf(IOException.class, failure(bomb));
    }

    @Test
    void detectsPrematureEofBeforeTheDeadlineAndPreservesHttpErrorResponses() throws Exception {
        ProxyFetchCall truncated = fetch.fetch(ProxyFetchRequest.builder(uri("/truncated"))
                .timeout(Duration.ofSeconds(10)).build());
        assertInstanceOf(IOException.class, failure(truncated));
        ProxyFetchResult missing = fetch.fetchBlocking(request("/missing"));
        assertEquals(404, missing.status());
        assertEquals("missing", missing.bodyAsString());
    }

    @Test
    void deadlineCancelsAStalledFetchAndReleasesAdmission() throws Exception {
        long started = System.nanoTime();
        ProxyFetchCall call = fetch.fetch(ProxyFetchRequest.builder(uri("/stall"))
                .timeout(Duration.ofMillis(150)).build());
        assertInstanceOf(TimeoutException.class, failure(call));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, fetch.activeRequests()));
    }

    @Test
    void explicitCancellationDoesNotPermitExternalStageMutation() throws Exception {
        ProxyFetchCall call = fetch.fetch(ProxyFetchRequest.builder(uri("/stall"))
                .timeout(Duration.ofSeconds(10)).build());
        assertTrue(stalled.await(3, TimeUnit.SECONDS));
        call.completion().toCompletableFuture().complete(null);
        assertFalse(call.completion().toCompletableFuture().isDone());
        IOException cancelled = new IOException("Caller cancelled fetch");
        call.cancel(cancelled);
        assertSame(cancelled, failure(call));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, fetch.activeRequests()));
        call.cancel(new IOException("Late cancellation"));
    }

    @Test
    void blockingInterruptionCancelsWithoutWaitingForCleanupAndPreservesInterruptStatus() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                fetch.fetchBlocking(ProxyFetchRequest.builder(uri("/stall"))
                        .timeout(Duration.ofSeconds(10)).build());
            } catch (Throwable cause) {
                failure.set(cause);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "fetch-blocking-caller");
        caller.start();
        try {
            assertTrue(stalled.await(3, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2000);
            assertFalse(caller.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            assertTrue(interrupted.get());
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, fetch.activeRequests()));
        } finally {
            releaseStall.countDown();
            caller.interrupt();
            caller.join(2000);
        }
    }

    @Test
    void closeCancelsItsOwnCallsAndLeavesSharedHttpClientUsable() throws Exception {
        ProxyFetchCall call = fetch.fetch(request("/stall"));
        assertTrue(stalled.await(3, TimeUnit.SECONDS));
        fetch.close();
        assertInstanceOf(IOException.class, failure(call));
        assertTrue(httpClient.isStarted());
        assertInstanceOf(IOException.class, failure(fetch.fetch(request("/missing"))));
        try (ProxyFetchClient other = new ProxyFetchClient(httpClient, JettyProxyProperties.defaults())) {
            assertEquals(404, other.fetchBlocking(request("/missing")).status());
        }
    }

    @Test
    void usesAModestDefaultBudgetAndSupportsAnIndependentExplicitFetchLimit() throws Exception {
        assertEquals(ProxyFetchOptions.defaults(), fetch.options());
        fetch.close();
        ProxyFetchOptions options = new ProxyFetchOptions(1, 1, 1);
        fetch = new ProxyFetchClient(httpClient, JettyProxyProperties.defaults(), options);
        assertEquals(options, fetch.options());
        ProxyFetchCall first = fetch.fetch(request("/stall"));
        assertTrue(stalled.await(3, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure(fetch.fetch(request("/missing"))));
        assertEquals(1, fetch.activeRequests());
        first.cancel(new IOException("Budget test complete"));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, fetch.activeRequests()));
        assertEquals(404, fetch.fetchBlocking(request("/missing")).status());
    }

    @Test
    void cancellingAQueuedDecoderReleasesInputWithoutWaitingForOtherBusyWorkers() throws Exception {
        fetch.close();
        JettyProxyProperties defaults = JettyProxyProperties.defaults();
        fetch = new ProxyFetchClient(httpClient, new JettyProxyProperties(defaults.upstream(), 1024, 16_384,
                1, 2, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(10)));
        var field = ProxyFetchClient.class.getDeclaredField("decoder");
        field.setAccessible(true);
        ThreadPoolExecutor workers = (ThreadPoolExecutor)field.get(fetch);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) workers.execute(() -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            ProxyFetchCall call = fetch.fetch(ProxyFetchRequest.builder(uri("/binary"))
                    .timeout(Duration.ofSeconds(10)).build());
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(1, workers.getQueue().size()));

            IOException cancelled = new IOException("Queued fetch cancelled");
            call.cancel(cancelled);
            assertSame(cancelled, failure(call));
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertEquals(0, fetch.activeRequests()));
            assertEquals(0, workers.getQueue().size());
            assertEquals(1, release.getCount(), "Unrelated workers must still be blocked during recovery");
            ProxyFetchResult recovered = fetch.fetchBlocking(ProxyFetchRequest.builder(uri("/local-options"))
                    .method("OPTIONS").headers(Map.of("Max-Forwards", List.of("0"))).build());
            assertEquals(200, recovered.status());
        } finally { release.countDown(); }
    }

    @Test
    void headIgnoresRepresentationLengthForWireLimitAndNonblockingInvocationCannotBlock() throws Exception {
        ProxyFetchResult head = fetch.fetchBlocking(ProxyFetchRequest.builder(uri("/head"))
                .method("HEAD").maxWireBytes(1).decodeContentEncoding(true).build());
        assertEquals(200, head.status());
        assertEquals(0, head.bodyLength());
        Invocable.invokeNonBlocking(() -> assertThrows(IllegalStateException.class,
                () -> fetch.fetchBlocking(request("/missing"))));
    }

    @ParameterizedTest
    @CsvSource({"GET,204,false", "GET,205,false", "HEAD,200,true", "GET,304,true"})
    void unsupportedTransferCodingIsOnlyPermittedAsHeadOr304Metadata(String method, int status, boolean metadata)
            throws Exception {
        try (ServerSocket raw = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            var responder = serverWorkers.submit(() -> {
                try (var socket = raw.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    socket.getOutputStream().write(("HTTP/1.1 " + status + " Fixture\r\n"
                            + "Transfer-Encoding: gzip, chunked\r\nConnection: close\r\n\r\n"
                            + (status == 205 ? "0\r\n\r\n" : "")).getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                return null;
            });
            ProxyFetchRequest prepared = ProxyFetchRequest.builder(URI.create("http://127.0.0.1:" + raw.getLocalPort() + "/metadata"))
                    .method(method).maxWireBytes(1).decodeContentEncoding(true).timeout(Duration.ofSeconds(10)).build();
            ProxyFetchCall call = fetch.fetch(prepared);
            if (metadata) {
                ProxyFetchResult result = call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(status, result.status());
                assertEquals(0, result.bodyLength());
            } else {
                assertInstanceOf(IOException.class, failure(call));
            }
            responder.get(3, TimeUnit.SECONDS);
        }
    }

    private ProxyFetchRequest request(String path) { return ProxyFetchRequest.builder(uri(path)).build(); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }

    private static Throwable failure(ProxyFetchCall call) throws Exception {
        Throwable failure = call.completion().handle((result, problem) -> problem).toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertNotNull(failure, "The failed fetch must not return a successful response");
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        try {
            if (path.startsWith("/inspect")) {
                int requestBytes = exchange.getRequestBody().readAllBytes().length;
                exchange.getResponseHeaders().set("X-Received-Host", exchange.getRequestHeaders().getFirst("Host"));
                send(exchange, 200, (exchange.getRequestMethod() + "|" + exchange.getRequestURI().toASCIIString()
                        + "|" + exchange.getRequestHeaders().getFirst("X-Test") + "|"
                        + java.util.Objects.requireNonNullElse(exchange.getRequestHeaders().getFirst("X-Excluded"), "none")
                        + "|" + requestBytes).getBytes(StandardCharsets.UTF_8));
            } else switch (path) {
                case "/cookies" -> {
                    exchange.getResponseHeaders().add("Set-Cookie", "first=1; Path=/");
                    exchange.getResponseHeaders().add("Set-Cookie", "second=2; Path=/");
                    send(exchange, 200, PLAIN);
                }
                case "/cookie-inspect" -> send(exchange, 200, java.util.Objects.requireNonNullElse(
                        exchange.getRequestHeaders().getFirst("Cookie"), "none").getBytes(StandardCharsets.US_ASCII));
                case "/redirect" -> {
                    exchange.getResponseHeaders().set("Location", "/cookies");
                    send(exchange, 302, "redirect".getBytes(StandardCharsets.US_ASCII));
                }
                case "/binary" -> send(exchange, 200, BINARY);
                case "/gzip", "/deflate", "/gzip-bomb" -> {
                    exchange.getResponseHeaders().set("Content-Encoding", path.equals("/deflate") ? "deflate" : "gzip");
                    exchange.getResponseHeaders().set("ETag", "\"encoded-representation\"");
                    send(exchange, 200, path.equals("/deflate") ? deflate : path.equals("/gzip-bomb") ? gzip(new byte[32 * 1024]) : gzip);
                }
                case "/charset" -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=\"windows-1251\"");
                    send(exchange, 200, "Привет".getBytes(Charset.forName("windows-1251")));
                }
                case "/declared" -> send(exchange, 200, new byte[8192]);
                case "/chunked" -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(new byte[8192]);
                }
                case "/truncated" -> {
                    exchange.sendResponseHeaders(200, 1000);
                    exchange.getResponseBody().write(new byte[] {1, 2, 3});
                }
                case "/stall" -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().flush();
                    stalled.countDown();
                    releaseStall.await(10, TimeUnit.SECONDS);
                    exchange.getResponseBody().write(PLAIN);
                }
                case "/head" -> {
                    exchange.getResponseHeaders().set("Content-Length", "1000000");
                    exchange.sendResponseHeaders(200, -1);
                }
                default -> send(exchange, 404, "missing".getBytes(StandardCharsets.US_ASCII));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException expectedOnCancelOrDeliberateTruncation) {
            // Limit/cancel tests intentionally close upstream while it is still writing.
        } finally { exchange.close(); }
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var encoder = new GZIPOutputStream(output)) { encoder.write(body); }
        return output.toByteArray();
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
