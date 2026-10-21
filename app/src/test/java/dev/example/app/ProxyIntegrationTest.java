package dev.example.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.proxy.api.RequestProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real sockets and the real embedded Jetty: mocks cannot verify non-blocking I/O. */
@Timeout(15)
class ProxyIntegrationTest {
    private static HttpServer upstream;
    private static ServerSocket rawUpstream;
    private static ExecutorService backendThreads;
    private static ConfigurableApplicationContext application;
    private static HttpClient client;
    private static int port;
    private static final AtomicReference<CountDownLatch> streamGate = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> stallGate = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> truncateGate = new AtomicReference<>();
    private static final AtomicInteger ambiguousPathProcessorCalls = new AtomicInteger();
    private static final AtomicInteger forwardedRequests = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendThreads = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "test-upstream");
            thread.setDaemon(true);
            return thread;
        });
        upstream.setExecutor(backendThreads);
        upstream.createContext("/", ProxyIntegrationTest::serve);
        upstream.start();
        rawUpstream = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        backendThreads.execute(ProxyIntegrationTest::acceptRawConnections);
        application = new SpringApplicationBuilder(ProxyApplication.class, Customization.class)
                .run("--server.port=0",
                        "--proxy.upstream=http://127.0.0.1:" + upstream.getAddress().getPort(),
                        "--proxy.allowed-origins[0]=http://127.0.0.1:" + rawUpstream.getLocalPort(),
                        "--proxy.exchange-timeout=2s", "--proxy.io-threads=2",
                        "--spring.main.banner-mode=off");
        // Spring Boot 4 moved web-server packages; only the public context contract matters here.
        Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
        port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterAll
    static void stop() {
        release(streamGate.get());
        release(stallGate.get());
        release(truncateGate.get());
        if (application != null) application.close();
        if (client != null) client.close();
        if (upstream != null) upstream.stop(0);
        if (rawUpstream != null) {
            try { rawUpstream.close(); } catch (IOException ignored) { }
        }
        if (backendThreads != null) backendThreads.shutdownNow();
    }

    @Test
    void streamsLargeBinaryUploadAndDownloadWithoutCorruption() throws Exception {
        byte[] bytes = new byte[2 * 1024 * 1024 + 137];
        new Random(42).nextBytes(bytes);
        HttpResponse<byte[]> response = client.send(request("/echo")
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(bytes, response.body());
    }

    @Test
    void forwardsUnknownLengthChunkedUploadWithoutParsingItsContentType() throws Exception {
        byte[] bytes = new byte[70_013];
        new Random(17).nextBytes(bytes);
        HttpResponse<byte[]> response = client.send(request("/echo")
                        .header("Content-Type", "multipart/form-data; boundary=opaque")
                        .method("PATCH", HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                        .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(bytes, response.body());
    }

    @Test
    void preservesCustomMethodRawEscapesAndRepeatedQueryParameters() throws Exception {
        String target = "/inspect/a%20b?x=1&x=2&encoded=%2F&plus=a+b";
        HttpResponse<String> response = client.send(request(target)
                        .method("FROB", HttpRequest.BodyPublishers.ofString("custom-body")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("FROB|" + target + "|processed|custom-body", response.body());
    }

    @Test
    void defaultJettyComplianceRejectsAmbiguousEncodingBeforeProcessorsRun() throws Exception {
        ambiguousPathProcessorCalls.set(0);
        HttpResponse<byte[]> response = client.send(request("/ambiguous/a%252Fb").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(400, response.statusCode());
        assertEquals(0, ambiguousPathProcessorCalls.get(),
                "Jetty must reject ambiguous encoded paths before the proxy customization hook");
    }

    @Test
    void invokesProcessorForHeadersAndUriCustomization() throws Exception {
        HttpResponse<String> response = client.send(request("/rewrite?ignored=1").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("GET|/inspect/rewritten?encoded=%2F&x=1&x=2|processed|", response.body());
    }

    @Test
    void removesConnectionNominatedHeadersAndPreservesRepeatedCookies() throws Exception {
        try (Socket socket = socket()) {
            socket.getOutputStream().write(("GET /proxy/headers HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close, X-Remove\r\nX-Remove: secret\r\nX-Keep: visible\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            RawResponse response = readResponse(socket.getInputStream());
            assertEquals(200, response.status());
            assertEquals("null|visible|processed", new String(response.body(), StandardCharsets.UTF_8));
            assertFalse(response.headers().containsKey("x-response-hop"));
            assertEquals(List.of("a=1; Path=/", "b=2; Path=/"), response.headers().get("set-cookie"));
        }
    }

    @Test
    void exposesFirstResponseBytesBeforeUpstreamHasFinished() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        streamGate.set(gate);
        try {
            HttpResponse<InputStream> response = client.send(request("/stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertArrayEquals("first".getBytes(StandardCharsets.US_ASCII), body.readNBytes(5));
                assertEquals(1, gate.getCount(), "Backend must still be waiting, not fully buffered");
                gate.countDown();
                assertEquals("last", new String(body.readAllBytes(), StandardCharsets.US_ASCII));
            }
        } finally {
            gate.countDown();
        }
    }

    @Test
    void preservesHeadContentLengthAndBodylessStatus() throws Exception {
        HttpResponse<byte[]> head = client.send(request("/head")
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, head.statusCode());
        assertEquals("123", head.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(0, head.body().length);
        HttpResponse<byte[]> empty = client.send(request("/empty").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(204, empty.statusCode());
        assertEquals(0, empty.body().length);
    }

    @Test
    void returnsGatewayTimeoutAndReleasesExchange() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        stallGate.set(gate);
        try {
            HttpResponse<byte[]> response = client.send(request("/stall").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(504, response.statusCode());
        } finally {
            gate.countDown();
        }
        HttpResponse<String> next = client.send(request("/inspect/after-timeout").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, next.statusCode());
    }

    @Test
    void forwardsEarlyFinalResponseWithoutWaitingForRemainingUpload() throws Exception {
        try (Socket socket = socket()) {
            socket.getOutputStream().write(("POST /proxy/early HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\nContent-Length: 1000000\r\n\r\npartial-upload")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            RawResponse response = readResponse(socket.getInputStream());
            assertEquals(413, response.status());
            assertEquals("rejected", new String(response.body(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void reusesCompletedUpstreamConnections() throws Exception {
        var upstreamPorts = new HashSet<String>();
        for (int i = 0; i < 6; i++) {
            HttpResponse<String> response = client.send(request("/connection").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            upstreamPorts.add(response.body());
        }
        assertTrue(upstreamPorts.size() <= 2,
                "Sequential completed exchanges should reuse at most one connection per configured I/O loop");
    }

    @Test
    void abortsCommittedDownstreamResponseWhenUpstreamChunkStreamIsTruncated() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        truncateGate.set(gate);
        try {
            HttpResponse<InputStream> response = client.send(request("/truncated").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertEquals("first", new String(body.readNBytes(5), StandardCharsets.US_ASCII));
                gate.countDown();
                assertThrows(IOException.class, body::readAllBytes,
                        "An incomplete upstream body must not become a successful downstream EOF");
            }
        } finally {
            gate.countDown();
        }
    }

    @Test
    void forwardsManyTinyChunksWithinOneReceiveBufferBudget() throws Exception {
        HttpResponse<String> response = client.send(request("/tiny-chunks").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("x".repeat(2000), response.body());
    }

    @Test
    void leavesLocalMvcAndSimilarPrefixesOutsideTheProxy() throws Exception {
        int before = forwardedRequests.get();
        HttpResponse<String> local = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/local")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, local.statusCode());
        assertEquals("local", local.body());
        HttpResponse<String> similarPrefix = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/proxying")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, similarPrefix.statusCode());
        assertEquals(before, forwardedRequests.get(), "Only the /proxy path segment is forwarded");
    }

    @Test
    void stripsExactPrefixAndTrailingSlashWhilePreservingRootQuery() throws Exception {
        for (String suffix : List.of("?x=1&x=2", "/?x=1&x=2")) {
            HttpResponse<String> response = client.send(request(suffix).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("root|/?x=1&x=2", response.body());
        }
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/proxy" + path))
                .timeout(Duration.ofSeconds(8));
    }

    private static Socket socket() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("localhost", port), 3000);
        socket.setSoTimeout(8000);
        return socket;
    }

    private static void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            forwardedRequests.incrementAndGet();
            String path = exchange.getRequestURI().getRawPath();
            if (path.equals("/")) {
                respond(exchange, 200, "root|" + exchange.getRequestURI().toASCIIString());
            } else if (path.equals("/echo")) {
                // Reading all bytes is deliberately confined to the test upstream.
                byte[] body = exchange.getRequestBody().readAllBytes();
                respond(exchange, 200, body);
            } else if (path.startsWith("/inspect")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(exchange, 200, exchange.getRequestMethod() + "|" + exchange.getRequestURI().toASCIIString()
                        + "|" + exchange.getRequestHeaders().getFirst("X-Processor") + "|" + body);
            } else if (path.equals("/headers")) {
                exchange.getResponseHeaders().add("Connection", "X-Response-Hop");
                exchange.getResponseHeaders().add("X-Response-Hop", "must-not-escape");
                exchange.getResponseHeaders().add("Set-Cookie", "a=1; Path=/");
                exchange.getResponseHeaders().add("Set-Cookie", "b=2; Path=/");
                respond(exchange, 200, exchange.getRequestHeaders().getFirst("X-Remove") + "|"
                        + exchange.getRequestHeaders().getFirst("X-Keep") + "|"
                        + exchange.getRequestHeaders().getFirst("X-Processor"));
            } else if (path.equals("/stream")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("first".getBytes(StandardCharsets.US_ASCII));
                exchange.getResponseBody().flush();
                await(streamGate.get());
                exchange.getResponseBody().write("last".getBytes(StandardCharsets.US_ASCII));
            } else if (path.equals("/head")) {
                exchange.getResponseHeaders().set("Content-Length", "123");
                exchange.sendResponseHeaders(200, -1);
            } else if (path.equals("/empty")) {
                exchange.sendResponseHeaders(204, -1);
            } else if (path.equals("/early")) {
                respond(exchange, 413, "rejected");
                exchange.getResponseBody().flush();
            } else if (path.equals("/stall")) {
                await(stallGate.get());
            } else if (path.equals("/connection")) {
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 200, Integer.toString(exchange.getRemoteAddress().getPort()));
            } else {
                respond(exchange, 404, "missing");
            }
        }
    }

    private static void acceptRawConnections() {
        while (!rawUpstream.isClosed()) {
            try {
                Socket socket = rawUpstream.accept();
                backendThreads.execute(() -> serveRaw(socket));
            } catch (IOException closedDuringTeardown) {
                return;
            }
        }
    }

    private static void serveRaw(Socket accepted) {
        try (Socket socket = accepted) {
            socket.setSoTimeout(8000);
            BufferedInputStream request = new BufferedInputStream(socket.getInputStream());
            String requestLine = line(request);
            for (String header; (header = line(request)) != null && !header.isEmpty(); ) {
                // Consume the request headers only. This endpoint receives a bodyless GET.
            }
            if (requestLine != null && requestLine.contains(" /tiny-chunks ")) {
                // About 12 KiB on the wire, but 2000 individually decoded HttpContent objects.
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n" + "1\r\nx\r\n".repeat(2000) + "0\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                return;
            }
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n5\r\nfirst\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            await(truncateGate.get());
            // Deliberately omit the mandatory zero-size final chunk and close the socket.
        } catch (IOException ignored) {
            // Teardown may close the listener when another test failed first.
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void await(CountDownLatch gate) throws IOException {
        try {
            if (gate == null || !gate.await(10, TimeUnit.SECONDS)) throw new IOException("Test gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static void release(CountDownLatch gate) {
        if (gate != null) gate.countDown();
    }

    private static RawResponse readResponse(InputStream source) throws IOException {
        BufferedInputStream input = new BufferedInputStream(source);
        int status;
        Map<String, List<String>> headers;
        do {
            String statusLine = line(input);
            if (statusLine == null) throw new IOException("EOF before response headers");
            status = Integer.parseInt(statusLine.split(" ", 3)[1]);
            headers = new LinkedHashMap<>();
            String header;
            while ((header = line(input)) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon < 1) throw new IOException("Malformed test response header");
                headers.computeIfAbsent(header.substring(0, colon).toLowerCase(java.util.Locale.ROOT),
                        ignored -> new ArrayList<>()).add(header.substring(colon + 1).trim());
            }
        } while (status >= 100 && status < 200);
        byte[] body;
        if (headers.getOrDefault("transfer-encoding", List.of()).stream()
                .anyMatch(value -> value.equalsIgnoreCase("chunked"))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (true) {
                String size = line(input);
                if (size == null) throw new IOException("EOF in chunk header");
                int count = Integer.parseInt(size.split(";", 2)[0], 16);
                if (count == 0) break;
                byte[] chunk = input.readNBytes(count);
                if (chunk.length != count) throw new IOException("Truncated chunk");
                bytes.write(chunk);
                if (!"".equals(line(input))) throw new IOException("Malformed chunk terminator");
            }
            body = bytes.toByteArray();
        } else if (headers.containsKey("content-length")) {
            int count = Integer.parseInt(headers.get("content-length").get(0));
            body = input.readNBytes(count);
            if (body.length != count) throw new IOException("Truncated response");
        } else {
            body = input.readAllBytes();
        }
        return new RawResponse(status, headers, body);
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        for (int next; (next = input.read()) != -1; ) {
            if (previous == '\r' && next == '\n') {
                byte[] value = bytes.toByteArray();
                return new String(value, 0, value.length - 1, StandardCharsets.US_ASCII);
            }
            bytes.write(next);
            previous = next;
            if (bytes.size() > 16384) throw new IOException("Response line too long");
        }
        return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.US_ASCII);
    }

    private record RawResponse(int status, Map<String, List<String>> headers, byte[] body) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Customization {
        @Bean
        RequestProcessor testProcessor() {
            return (incoming, outgoing, chain) -> {
                if (incoming.rawPath().startsWith("/ambiguous/")) ambiguousPathProcessorCalls.incrementAndGet();
                outgoing.headers().set("X-Processor", "processed");
                if (incoming.rawPath().equals("/rewrite")) {
                    URI original = outgoing.uri();
                    outgoing.uri(URI.create(original.getScheme() + "://" + original.getRawAuthority()
                            + "/inspect/rewritten?encoded=%2F&x=1&x=2"));
                } else if (incoming.rawPath().equals("/truncated") || incoming.rawPath().equals("/tiny-chunks")) {
                    outgoing.uri(URI.create("http://127.0.0.1:" + rawUpstream.getLocalPort() + incoming.rawPath()));
                }
                chain.proceed();
            };
        }
    }
}
