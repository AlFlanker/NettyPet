package dev.example.jettyclient;

import dev.example.jettyclient.proxy.JettyProxyServlet;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real servlet, embedded Jetty and outbound client over loopback sockets. */
@Timeout(20)
@Execution(ExecutionMode.SAME_THREAD)
class JettyProxyIntegrationTest {
    private static final byte[] LARGE_BODY = randomBytes(8 * 1024 * 1024 + 137, 42);
    private static final byte[] TINY_CHUNK_BODY = randomBytes(64 * 1024, 83);
    private static final byte[] TINY_CHUNK_WIRE = tinyChunkWire(TINY_CHUNK_BODY);
    private static final byte[] UPLOAD_PREFIX = "partial-upload".getBytes(StandardCharsets.US_ASCII);
    private static final String BOUNDARY = "Jetty-Proxy-Integrity-Boundary";
    private static final String MULTIPART_TYPE = "multipart/form-data; boundary=" + BOUNDARY;
    private static RawUpstream upstream;
    private static ConfigurableApplicationContext application;
    private static JettyProxyServlet servlet;
    private static HttpClient client;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        upstream = new RawUpstream();
        application = new SpringApplicationBuilder(JettyClientExampleApplication.class)
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--example.proxy.upstream=http://127.0.0.1:" + upstream.port() + "/base",
                        "--example.proxy.max-concurrent=1",
                        "--example.proxy.exchange-timeout=4s",
                        "--example.proxy.idle-timeout=10s");
        Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
        port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
        servlet = application.getBean(JettyProxyServlet.class);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void releasesAdmissionAndChecksFixture() {
        awaitReleased();
        assertNull(upstream.failure.get(), "The independent upstream fixture must complete correctly");
    }

    @AfterAll
    static void stop() throws Exception {
        if (upstream != null) upstream.releaseBody.countDown();
        if (application != null) application.close();
        if (client != null) client.close();
        if (upstream != null) upstream.close();
    }

    @Test
    void keepsLocalRouteAndMapsExactProxyPathWithUpstreamBasePrefix() throws Exception {
        HttpResponse<String> local = text("/local");
        assertEquals(200, local.statusCode());
        assertEquals("local", local.body());
        for (String path : List.of("/proxy", "/proxy/")) {
            HttpResponse<String> response = text(path);
            assertEquals(200, response.statusCode());
            assertEquals("GET|/base/", response.body());
            awaitReleased();
        }
    }

    @Test
    void preservesEncodedPathRawQueryAndCustomMethod() throws Exception {
        String suffix = "/inspect/a%20b/%E2%82%AC?x=1&x=2&encoded=%2F&plus=a+b&empty=";
        HttpResponse<String> response = client.send(request("/proxy" + suffix)
                        .method("FROB", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("FROB|/base" + suffix, response.body());
    }

    @Test
    void forwardsMultipartPostAndPutAsIdenticalWireBytes() throws Exception {
        byte[] body = multipartBody();
        for (String method : List.of("POST", "PUT")) {
            HttpResponse<byte[]> response = client.send(request("/proxy/echo")
                            .header("Content-Type", MULTIPART_TYPE)
                            .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals(method, response.headers().firstValue("X-Received-Method").orElseThrow());
            assertEquals(MULTIPART_TYPE, response.headers().firstValue("X-Received-Type").orElseThrow());
            assertArrayEquals(body, response.body(), "Part headers, Unicode, boundaries and binary bytes must survive");
            awaitReleased();
        }
    }

    @Test
    void forwardsUrlencodedPutWithoutServletFormParsing() throws Exception {
        byte[] body = "x=%2F&x=second+value&unicode=%D0%BC%D0%B8%D1%80&empty=&literal=%252F"
                .getBytes(StandardCharsets.US_ASCII);
        HttpResponse<byte[]> response = client.send(request("/proxy/echo")
                        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(body, response.body(), "FormContentFilter must not consume or reconstruct the proxy body");
    }

    @Test
    void streamsUnknownLengthChunkedUploadWithoutRequiringContentLength() throws Exception {
        byte[] body = randomBytes(256 * 1024 + 17, 19);
        HttpResponse<byte[]> response = client.send(request("/proxy/echo")
                        .header("Content-Type", "application/octet-stream")
                        .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("chunked", response.headers().firstValue("X-Received-Transfer-Encoding").orElseThrow());
        assertArrayEquals(body, response.body());
    }

    @Test
    void streamsEightMebibyteResponseWithoutCorruption() throws Exception {
        HttpResponse<byte[]> response = client.send(request("/proxy/large").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(LARGE_BODY, response.body());
    }

    @Test
    void preservesBinaryResponseSplitIntoSixtyFourThousandOneByteHttpChunks() throws Exception {
        HttpResponse<byte[]> response = client.send(request("/proxy/tiny-chunks").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(TINY_CHUNK_BODY, response.body(),
                "Batching must preserve every binary byte while removing HTTP chunk framing");
    }

    @Test
    void preservesContentEncodingAndCompressedWireBytes() throws Exception {
        HttpResponse<byte[]> response = client.send(request("/proxy/gzip").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("gzip", response.headers().firstValue("Content-Encoding").orElseThrow());
        assertArrayEquals(upstream.gzipBody, response.body(), "The proxy must not decode the upstream representation");
    }

    @Test
    void forwardsRepeatedSetCookieWithoutKeepingSharedUpstreamCookies() throws Exception {
        HttpResponse<String> first = text("/proxy/set-cookies");
        assertEquals(200, first.statusCode());
        assertEquals(List.of("first=1; Path=/", "second=2; Path=/"), first.headers().allValues("Set-Cookie"));
        awaitReleased();
        assertEquals("none", text("/proxy/cookie-inspect").body(), "Cookies must belong to the downstream client");
        awaitReleased();
        HttpResponse<String> explicit = client.send(request("/proxy/cookie-inspect")
                        .header("Cookie", "caller=present").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("caller=present", explicit.body());
    }

    @Test
    void forwardsRedirectStatusLocationAndBodyWithoutFollowingIt() throws Exception {
        HttpResponse<String> response = text("/proxy/redirect");
        assertEquals(302, response.statusCode());
        assertEquals("/destination?encoded=%2F", response.headers().firstValue("Location").orElseThrow());
        assertEquals("redirect-body", response.body());
    }

    @Test
    void waitsForFinalResponseAfter102And103WithoutLeakingInformationalHeaders() throws Exception {
        HttpResponse<String> response = text("/proxy/informational");
        assertEquals(200, response.statusCode());
        assertEquals("final-response", response.body());
        assertTrue(response.headers().allValues("Link").isEmpty(), "Early Hint headers are not final response headers");
    }

    @Test
    void waitsForFinalResponseAfterUnknown199InformationalStatus() throws Exception {
        HttpResponse<String> response = text("/proxy/informational-199");
        assertEquals(200, response.statusCode());
        assertEquals("after-199", response.body());
        assertTrue(response.headers().allValues("X-Interim").isEmpty());
        awaitReleased();
    }

    @Test
    void rejectsUnsolicitedUpstream101AndRecoversAdmission() throws Exception {
        upstream.upgradeClosed = new CountDownLatch(1);
        HttpResponse<String> response = text("/proxy/unsolicited-upgrade");
        assertEquals(502, response.statusCode());
        assertTrue(upstream.upgradeClosed.await(2, TimeUnit.SECONDS), "Rejected protocol upgrade must close upstream");
        awaitReleased();
        HttpResponse<String> recovered = text("/proxy/ok");
        assertEquals(200, recovered.statusCode());
        assertEquals("ok", recovered.body());
    }

    @Test
    void publishesHeadersBeforeDelayedFirstBodyBytes() throws Exception {
        upstream.releaseBody = new CountDownLatch(1);
        try (Socket socket = downstreamSocket()) {
            send(socket, "GET /proxy/delayed-body HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            InputStream input = new BufferedInputStream(socket.getInputStream());
            ResponseHead head = readHead(input);
            assertEquals(200, head.status());
            assertEquals("ready", head.headers().get("x-upstream-headers").getFirst());
            assertEquals(1, upstream.releaseBody.getCount(), "Receiving headers must not require a body byte");
            upstream.releaseBody.countDown();
            assertArrayEquals("first-last".getBytes(StandardCharsets.US_ASCII), readBody(input, head.headers()));
        } finally {
            upstream.releaseBody.countDown();
        }
    }

    @Test
    void deliversFirstChunkBeforeUpstreamCompletesBody() throws Exception {
        upstream.releaseBody = new CountDownLatch(1);
        try (Socket socket = downstreamSocket()) {
            send(socket, "GET /proxy/stream HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            InputStream input = new BufferedInputStream(socket.getInputStream());
            ResponseHead head = readHead(input);
            assertEquals(200, head.status());
            // A fixed-length upstream also keeps downstream framing deterministic here.
            assertFalse(head.headers().containsKey("transfer-encoding"));
            assertArrayEquals("first".getBytes(StandardCharsets.US_ASCII), input.readNBytes(5));
            assertEquals(1, upstream.releaseBody.getCount(), "The proxy must stream before the final upstream write");
            upstream.releaseBody.countDown();
            assertArrayEquals("-last".getBytes(StandardCharsets.US_ASCII), input.readNBytes(5));
        } finally {
            upstream.releaseBody.countDown();
        }
    }

    @Test
    void forwardsEarly413BodyWhileClientStillHasUnsentUpload() throws Exception {
        try (Socket socket = downstreamSocket()) {
            send(socket, "POST /proxy/early HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + "Content-Length: 1000000\r\n\r\n" + new String(UPLOAD_PREFIX, StandardCharsets.US_ASCII));
            InputStream input = new BufferedInputStream(socket.getInputStream());
            ResponseHead head = readHead(input);
            assertEquals(413, head.status());
            assertArrayEquals("too-large".getBytes(StandardCharsets.US_ASCII), readBody(input, head.headers()));
        }
    }

    @Test
    void rejectsGlobalSaturationAndRecoversAfterExchangeTimeout() throws Exception {
        upstream.stallEntered = new CountDownLatch(1);
        upstream.stallClosed = new CountDownLatch(1);
        var stalled = client.sendAsync(request("/proxy/stall").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(upstream.stallEntered.await(2, TimeUnit.SECONDS), "The first request must reach upstream");
        assertEquals(1, servlet.activeRequests());
        int before = upstream.requests.get();
        HttpResponse<String> rejected = text("/proxy/ok");
        assertEquals(503, rejected.statusCode());
        assertEquals(before, upstream.requests.get(), "Admission rejection must happen before upstream I/O");
        assertEquals(504, stalled.get(8, TimeUnit.SECONDS).statusCode());
        assertTrue(upstream.stallClosed.await(2, TimeUnit.SECONDS), "Timeout must close the upstream socket");
        awaitReleased();
        assertEquals("ok", text("/proxy/ok").body());
    }

    @Test
    void clientResetBeforeResponseHeadersEventuallyClosesUpstreamAndReleasesAdmission() throws Exception {
        upstream.stallEntered = new CountDownLatch(1);
        upstream.stallClosed = new CountDownLatch(1);
        try (Socket socket = downstreamSocket()) {
            send(socket, "GET /proxy/stall HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertTrue(upstream.stallEntered.await(2, TimeUnit.SECONDS), "The request must reach the stalled upstream");
            socket.setSoLinger(true, 0);
        }
        // No Servlet read/write is outstanding here; the absolute 4s deadline must still bound cleanup.
        // Earlier disconnect detection is also valid and must not make this test fail.
        assertTrue(upstream.stallClosed.await(6, TimeUnit.SECONDS), "Upstream closure must remain bounded after downstream reset");
        awaitReleased();
        HttpResponse<String> recovered = text("/proxy/ok");
        assertEquals(200, recovered.statusCode());
        assertEquals("ok", recovered.body());
    }

    @Test
    void clientResetDuringUploadClosesUpstreamAndReleasesAdmissionBeforeDeadline() throws Exception {
        upstream.uploadEntered = new CountDownLatch(1);
        upstream.uploadClosed = new CountDownLatch(1);
        try (Socket socket = downstreamSocket()) {
            send(socket, "POST /proxy/disconnect-upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 1000000\r\n\r\n" + new String(UPLOAD_PREFIX, StandardCharsets.US_ASCII));
            assertTrue(upstream.uploadEntered.await(2, TimeUnit.SECONDS), "Partial upload must reach upstream");
            assertEquals(1, servlet.activeRequests());
            socket.setSoLinger(true, 0);
        }
        assertTrue(upstream.uploadClosed.await(2, TimeUnit.SECONDS), "Client reset must close upstream before the 4s deadline");
        awaitReleased();
        assertEquals("ok", text("/proxy/ok").body());
    }

    @Test
    void nonReadingClientResetDuringLargeResponseClosesUpstreamAndReleasesAdmission() throws Exception {
        upstream.downloadEntered = new CountDownLatch(1);
        upstream.downloadClosed = new CountDownLatch(1);
        try (Socket socket = downstreamSocket()) {
            send(socket, "GET /proxy/disconnect-download HTTP/1.1\r\nHost: localhost\r\n\r\n");
            ResponseHead head = readHead(new BufferedInputStream(socket.getInputStream()));
            assertEquals(200, head.status());
            assertTrue(upstream.downloadEntered.await(2, TimeUnit.SECONDS), "The large streaming response must have started");
            assertEquals(1, servlet.activeRequests());
            // Read no body and reset. No assertion depends on either socket's kernel buffer size.
            socket.setSoLinger(true, 0);
        }
        assertTrue(upstream.downloadClosed.await(2, TimeUnit.SECONDS),
                "Downstream reset must physically close upstream before the 4s exchange deadline");
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertEquals(0, servlet.activeRequests()));
        HttpResponse<String> recovered = text("/proxy/ok");
        assertEquals(200, recovered.statusCode());
        assertEquals("ok", recovered.body());
    }

    @Test
    void applicationShutdownClosesActiveUpstreamAndReleasesAdmission() throws Exception {
        try (RawUpstream isolatedUpstream = new RawUpstream();
             ConfigurableApplicationContext isolatedApplication = new SpringApplicationBuilder(JettyClientExampleApplication.class)
                     .run("--server.port=0", "--server.shutdown=immediate", "--spring.main.banner-mode=off",
                             "--example.proxy.upstream=http://127.0.0.1:" + isolatedUpstream.port() + "/base",
                             "--example.proxy.max-concurrent=1", "--example.proxy.exchange-timeout=30s",
                             "--example.proxy.idle-timeout=30s")) {
            isolatedUpstream.stallEntered = new CountDownLatch(1);
            isolatedUpstream.stallClosed = new CountDownLatch(1);
            Object webServer = isolatedApplication.getClass().getMethod("getWebServer").invoke(isolatedApplication);
            int isolatedPort = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
            JettyProxyServlet isolatedServlet = isolatedApplication.getBean(JettyProxyServlet.class);
            var pending = client.sendAsync(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + isolatedPort + "/proxy/stall"))
                            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(isolatedUpstream.stallEntered.await(2, TimeUnit.SECONDS), "An exchange must be active at shutdown");
                assertEquals(1, isolatedServlet.activeRequests());
                isolatedApplication.close();
                assertTrue(isolatedUpstream.stallClosed.await(2, TimeUnit.SECONDS),
                        "Shutdown must close the physical upstream connection without waiting for the 30s exchange timeout");
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, isolatedServlet.activeRequests()));
                // Immediate container shutdown can produce either an HTTP error or a downstream connection failure.
                pending.handle((response, failure) -> null).get(3, TimeUnit.SECONDS);
                assertNull(isolatedUpstream.failure.get(), "The isolated shutdown fixture must complete correctly");
            } finally {
                pending.cancel(true);
            }
        }
        assertEquals("local", text("/local").body(), "The shared application must remain running");
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    private static HttpResponse<String> text(String path) throws IOException, InterruptedException {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void awaitReleased() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, servlet.activeRequests()));
    }

    private static Socket downstreamSocket() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        socket.setSoTimeout(3000);
        return socket;
    }

    private static void send(Socket socket, String wire) throws IOException {
        socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] tinyChunkWire(byte[] body) {
        ByteArrayOutputStream wire = new ByteArrayOutputStream(body.length * 6 + 5);
        for (byte value : body) {
            wire.write('1');
            wire.write('\r');
            wire.write('\n');
            wire.write(value);
            wire.write('\r');
            wire.write('\n');
        }
        wire.write('0');
        wire.write('\r');
        wire.write('\n');
        wire.write('\r');
        wire.write('\n');
        return wire.toByteArray();
    }

    private static byte[] multipartBody() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"comment\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\nПривет, мир! €\r\n"
                + "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"данные.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 1024; i++) for (int value = 0; value < 256; value++) bytes.write(value);
        bytes.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return bytes.toByteArray();
    }

    private record ResponseHead(int status, Map<String, List<String>> headers) { }

    private static ResponseHead readHead(InputStream input) throws IOException {
        String status = line(input);
        if (status == null) throw new EOFException("Missing HTTP status");
        return new ResponseHead(Integer.parseInt(status.split(" ", 3)[1]), headers(input));
    }

    private static Map<String, List<String>> headers(InputStream input) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String value; (value = line(input)) != null && !value.isEmpty(); ) {
            int colon = value.indexOf(':');
            if (colon <= 0) throw new IOException("Invalid fixture header: " + value);
            headers.computeIfAbsent(value.substring(0, colon).toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(value.substring(colon + 1).trim());
        }
        return headers;
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < 16 * 1024) {
            int value = input.read();
            if (value == -1) return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.ISO_8859_1);
            if (value == '\n') {
                byte[] result = bytes.toByteArray();
                if (result.length == 0 || result[result.length - 1] != '\r') throw new IOException("Missing CRLF");
                return new String(result, 0, result.length - 1, StandardCharsets.ISO_8859_1);
            }
            bytes.write(value);
        }
        throw new IOException("Fixture HTTP line too long");
    }

    private static byte[] readBody(InputStream input, Map<String, List<String>> headers) throws IOException {
        if (headers.containsKey("transfer-encoding")) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (;;) {
                String framing = line(input);
                if (framing == null) throw new EOFException("Missing chunk size");
                int size = Integer.parseInt(framing.split(";", 2)[0], 16);
                if (size == 0) { headers(input); return body.toByteArray(); }
                byte[] chunk = input.readNBytes(size);
                if (chunk.length != size) throw new EOFException("Truncated chunk");
                body.write(chunk);
                if (!"".equals(line(input))) throw new IOException("Missing chunk CRLF");
            }
        }
        if (!headers.containsKey("content-length")) return new byte[0];
        int size = Integer.parseInt(headers.get("content-length").getFirst());
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException("Truncated fixed-length body");
        return bytes;
    }

    /** A separate wire-level endpoint avoids accidental decoding or cookie/redirect handling in the fixture. */
    private static final class RawUpstream implements AutoCloseable {
        final ServerSocket listener = new ServerSocket();
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger requests = new AtomicInteger();
        final byte[] gzipBody;
        volatile CountDownLatch releaseBody = new CountDownLatch(0);
        volatile CountDownLatch stallEntered = new CountDownLatch(0);
        volatile CountDownLatch stallClosed = new CountDownLatch(0);
        volatile CountDownLatch uploadEntered = new CountDownLatch(0);
        volatile CountDownLatch uploadClosed = new CountDownLatch(0);
        volatile CountDownLatch downloadEntered = new CountDownLatch(0);
        volatile CountDownLatch downloadClosed = new CountDownLatch(0);
        volatile CountDownLatch upgradeClosed = new CountDownLatch(0);

        RawUpstream() throws IOException {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(randomBytes(128 * 1024 + 13, 23));
            }
            gzipBody = compressed.toByteArray();
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            workers.execute(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket socket = listener.accept();
                        sockets.add(socket);
                        workers.execute(() -> serve(socket));
                    } catch (IOException closing) {
                        if (!listener.isClosed()) failure.compareAndSet(null, closing);
                        return;
                    }
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        private void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(10_000);
                InputStream input = new BufferedInputStream(socket.getInputStream());
                String requestLine = line(input);
                if (requestLine == null) return;
                requests.incrementAndGet();
                String[] parts = requestLine.split(" ", 3);
                String target = parts[1];
                String path = target.split("\\?", 2)[0];
                Map<String, List<String>> received = headers(input);
                if (path.equals("/base/stall")) {
                    stallEntered.countDown();
                    observeClose(input, stallClosed);
                    return;
                }
                if (path.equals("/base/disconnect-upload")) {
                    assertArrayEquals(UPLOAD_PREFIX, input.readNBytes(UPLOAD_PREFIX.length));
                    uploadEntered.countDown();
                    observeClose(input, uploadClosed);
                    return;
                }
                if (path.equals("/base/early")) {
                    assertArrayEquals(UPLOAD_PREFIX, input.readNBytes(UPLOAD_PREFIX.length));
                    respond(socket, 413, Map.of(), "too-large".getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                byte[] body = readBody(input, received);
                switch (path) {
                    case "/base/disconnect-download" -> {
                        OutputStream output = socket.getOutputStream();
                        byte[] block = new byte[64 * 1024];
                        int blocks = 4096;
                        send(socket, "HTTP/1.1 200 OK\r\nContent-Length: " + ((long) block.length * blocks)
                                + "\r\nConnection: close\r\n\r\n");
                        output.write(block);
                        output.flush();
                        downloadEntered.countDown();
                        workers.execute(() -> {
                            try {
                                for (int i = 1; i < blocks; i++) output.write(block);
                                output.flush();
                            } catch (IOException expectedCancellation) {
                                // The downstream RST must interrupt this bounded producer's upstream connection.
                            }
                        });
                        // Observe the peer independently even if the producer is blocked in a socket write.
                        observeClose(input, downloadClosed);
                    }
                    case "/base/echo" -> respond(socket, 200, Map.of(
                            "X-Received-Method", List.of(parts[0]),
                            "X-Received-Type", received.getOrDefault("content-type", List.of("none")),
                            "X-Received-Transfer-Encoding", received.getOrDefault("transfer-encoding", List.of("none"))), body);
                    case "/base/large" -> respond(socket, 200, Map.of(), LARGE_BODY);
                    case "/base/tiny-chunks" -> {
                        send(socket, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
                        // Prebuilt bounded wire data is emitted in one write; the fixture does not pace individual chunks.
                        socket.getOutputStream().write(TINY_CHUNK_WIRE);
                        socket.getOutputStream().flush();
                    }
                    case "/base/gzip" -> respond(socket, 200, Map.of("Content-Encoding", List.of("gzip")), gzipBody);
                    case "/base/set-cookies" -> respond(socket, 200,
                            Map.of("Set-Cookie", List.of("first=1; Path=/", "second=2; Path=/")), new byte[0]);
                    case "/base/cookie-inspect" -> respond(socket, 200, Map.of(),
                            String.join("; ", received.getOrDefault("cookie", List.of("none"))).getBytes(StandardCharsets.UTF_8));
                    case "/base/redirect" -> respond(socket, 302,
                            Map.of("Location", List.of("/destination?encoded=%2F")), "redirect-body".getBytes(StandardCharsets.US_ASCII));
                    case "/base/informational" -> {
                        send(socket, "HTTP/1.1 102 Processing\r\n\r\nHTTP/1.1 103 Early Hints\r\n"
                                + "Link: </style.css>; rel=preload\r\n\r\n");
                        respond(socket, 200, Map.of(), "final-response".getBytes(StandardCharsets.US_ASCII));
                    }
                    case "/base/informational-199" -> {
                        send(socket, "HTTP/1.1 199 Diagnostic\r\nX-Interim: ignore\r\n\r\n");
                        respond(socket, 200, Map.of(), "after-199".getBytes(StandardCharsets.US_ASCII));
                    }
                    case "/base/unsolicited-upgrade" -> {
                        send(socket, "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n");
                        // Keep the socket open: a later EOF must not make a missing 101 rejection look correct.
                        observeClose(input, upgradeClosed);
                    }
                    case "/base/delayed-body", "/base/stream" -> {
                        OutputStream output = socket.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n"
                                + "X-Upstream-Headers: ready\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        if (path.equals("/base/stream")) output.write("first".getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                        if (!releaseBody.await(8, TimeUnit.SECONDS)) throw new IOException("Body gate timed out");
                        output.write((path.equals("/base/stream") ? "-last" : "first-last").getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                    }
                    case "/base/ok" -> respond(socket, 200, Map.of(), "ok".getBytes(StandardCharsets.US_ASCII));
                    default -> respond(socket, 200, Map.of(), (parts[0] + "|" + target).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable unexpected) {
                failure.compareAndSet(null, unexpected);
            } finally {
                sockets.remove(socket);
            }
        }

        private static void observeClose(InputStream input, CountDownLatch closed) throws IOException {
            try {
                while (input.read() != -1) { }
            } catch (SocketException reset) {
                // A peer reset also proves physical connection closure.
            }
            closed.countDown();
        }

        private static void respond(Socket socket, int status, Map<String, List<String>> headers, byte[] body)
                throws IOException {
            OutputStream output = socket.getOutputStream();
            StringBuilder wire = new StringBuilder("HTTP/1.1 ").append(status).append(" Fixture\r\n")
                    .append("Content-Length: ").append(body.length).append("\r\nConnection: close\r\n");
            headers.forEach((name, values) -> values.forEach(value -> wire.append(name).append(": ").append(value).append("\r\n")));
            output.write(wire.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
            output.write(body);
            output.flush();
        }

        @Override
        public void close() throws Exception {
            releaseBody.countDown();
            listener.close();
            for (Socket socket : sockets) socket.close();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
