package dev.example.jettyclient;

import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.ProxyHeaderCustomizer;
import dev.example.jettyclient.proxy.ProxyHeaderEditor;
import dev.example.jettyclient.proxy.ProxyRequestMetadata;
import dev.example.jettyclient.proxy.ProxyRequestPlan;
import dev.example.jettyclient.proxy.ProxyRequestPlanner;
import dev.example.jettyclient.proxy.ProxyResponseBody;
import dev.example.jettyclient.proxy.ProxyResponseBodyPolicy;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** A transformed resource must not reuse the origin representation's HEAD/cache metadata. */
@Timeout(20)
@Execution(ExecutionMode.SAME_THREAD)
class JettyProxyRewriteMetadataTest {
    private static final byte[] ORIGINAL = ascii("before old-link after");
    private static final byte[] TRANSFORMED = ascii("before new after");
    private static final String ORIGIN_ETAG = "\"origin-v1\"";
    private static final String ORIGIN_MODIFIED = "Wed, 21 Oct 2015 07:28:00 GMT";
    private static final List<String> REPRESENTATION_HEADERS = List.of("Content-Length", "ETag", "Last-Modified",
            "Content-MD5", "Digest", "Content-Digest", "Repr-Digest", "Accept-Ranges");
    private static RawUpstream upstream;
    private static ConfigurableApplicationContext application;
    private static JettyProxyEngine engine;
    private static HttpClient client;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        upstream = new RawUpstream();
        application = new SpringApplicationBuilder(JettyClientExampleApplication.class, MetadataRoutes.class)
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--jetty.proxy.exchange-timeout=5s");
        Object server = application.getClass().getMethod("getWebServer").invoke(application);
        port = (int)server.getClass().getMethod("getPort").invoke(server);
        engine = application.getBean(JettyProxyEngine.class);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void released() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(0, engine.activeRequests()));
        assertNull(upstream.failure.get(), "The raw origin fixture must finish without unexpected failures");
    }

    @AfterAll
    static void stop() throws Exception {
        if (application != null) application.close();
        if (client != null) client.close();
        if (upstream != null) upstream.close();
    }

    @Test
    void headForTheSameRewrittenResourceDoesNotAdvertiseTheOriginLengthOrValidators() throws Exception {
        HttpResponse<byte[]> get = client.send(request("resource").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, get.statusCode());
        assertArrayEquals(TRANSFORMED, get.body());
        assertEquals(Integer.toString(TRANSFORMED.length), get.headers().firstValue("Content-Length").orElseThrow());
        assertTrue(get.headers().allValues("ETag").isEmpty());

        HttpResponse<byte[]> head = client.send(request("resource")
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        assertEquals("text/html", head.headers().firstValue("Content-Type").orElseThrow());
        assertRepresentationMetadataAbsent(head);
    }

    @Test
    void originCacheValidatorsAreNotUsedToReturn304InsteadOfTheTransformedGet() throws Exception {
        HttpResponse<byte[]> response = client.send(request("resource")
                        .header("If-None-Match", ORIGIN_ETAG)
                        .header("If-Modified-Since", ORIGIN_MODIFIED).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode(), "Origin validators describe different bytes from the rewritten resource");
        assertArrayEquals(TRANSFORMED, response.body());
        assertNull(upstream.observed.get().headers().get("if-none-match"));
        assertNull(upstream.observed.get().headers().get("if-modified-since"));
    }

    @Test
    void wildcardIfNoneMatchStillChecksExistenceInRewriteMode() throws Exception {
        HttpResponse<byte[]> response = client.send(request("resource").header("If-None-Match", "*").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(304, response.statusCode(), "The wildcard precondition is independent of representation bytes");
        assertEquals(0, response.body().length);
        assertEquals("*", upstream.observed.get().headers().get("if-none-match"));
    }

    @Test
    void removingRangeInARequestHookAlsoRemovesTheOriginCacheValidatorForTheFullRewrite() throws Exception {
        HttpResponse<byte[]> response = client.send(request("range-removed")
                        .header("Range", "bytes=0-").header("If-None-Match", ORIGIN_ETAG).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertArrayEquals(TRANSFORMED, response.body());
        assertNull(upstream.observed.get().headers().get("range"));
        assertNull(upstream.observed.get().headers().get("if-none-match"));
    }

    @Test
    void anUnsolicited304DoesNotExposeTheOriginalRepresentationMetadataInRewriteMode() throws Exception {
        HttpResponse<byte[]> response = client.send(request("unsolicited").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(304, response.statusCode());
        assertEquals(0, response.body().length);
        assertRepresentationMetadataAbsent(response);
    }

    @Test
    void aQuotedCacheDirectiveArgumentDoesNotBecomeANoTransformDirective() throws Exception {
        HttpResponse<byte[]> response = client.send(request("quoted-cache").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertArrayEquals(TRANSFORMED, response.body());
        assertEquals("private=\"ETag, no-transform, X-Test\"",
                response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void writePreconditionsRemainIntactWhenThePlanHasAResponseBodyPolicy() throws Exception {
        HttpResponse<byte[]> response = client.send(request("resource").header("If-Match", "\"wrong\"")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(ascii("upload"))).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(412, response.statusCode());
        assertEquals("\"wrong\"", upstream.observed.get().headers().get("if-match"));
        assertEquals("PUT", upstream.observed.get().method());
        assertArrayEquals(ascii("precondition failed"), response.body());
    }

    @Test
    void passthroughPlansRetainHeadMetadataAndOriginCacheValidation() throws Exception {
        HttpResponse<byte[]> head = client.send(request("passthrough")
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, head.statusCode());
        assertEquals(Integer.toString(ORIGINAL.length), head.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(ORIGIN_ETAG, head.headers().firstValue("ETag").orElseThrow());

        HttpResponse<byte[]> conditional = client.send(request("passthrough")
                        .header("If-None-Match", ORIGIN_ETAG).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(304, conditional.statusCode());
        assertEquals(ORIGIN_ETAG, upstream.observed.get().headers().get("if-none-match"));
        assertEquals(ORIGIN_ETAG, conditional.headers().firstValue("ETag").orElseThrow());
    }

    private static void assertRepresentationMetadataAbsent(HttpResponse<?> response) {
        for (String header : REPRESENTATION_HEADERS)
            assertTrue(response.headers().allValues(header).isEmpty(), "Stale origin representation field: " + header);
    }

    private static HttpRequest.Builder request(String resource) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metadata/" + resource))
                .timeout(Duration.ofSeconds(8));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MetadataRoutes {
        @Bean
        ProxyRequestPlanner metadataPlanner() {
            ProxyResponseBodyPolicy rewrite = (request, response) -> {
                if (!response.headerValues("Content-Type").contains("text/html")) return null;
                return new ProxyResponseBodyPolicy.Selection(1024, 1024, (headers, input) -> {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    for (ByteBuffer buffer : input.buffers()) {
                        byte[] part = new byte[buffer.remaining()];
                        buffer.get(part);
                        bytes.writeBytes(part);
                    }
                    assertArrayEquals(ORIGINAL, bytes.toByteArray());
                    return ProxyResponseBody.of(ByteBuffer.wrap(TRANSFORMED));
                });
            };
            return incoming -> {
                String path = incoming.getRequestURI();
                if (!path.startsWith("/metadata/")) return null;
                String resource = path.substring("/metadata/".length());
                var plan = ProxyRequestPlan.builder(URI.create("http://127.0.0.1:" + upstream.port() + "/" + resource));
                if (!resource.equals("passthrough")) plan.responseBody(rewrite);
                if (resource.equals("range-removed")) {
                    plan.customizer(new ProxyHeaderCustomizer() {
                        @Override public void customizeRequest(ProxyRequestMetadata request, ProxyHeaderEditor headers) {
                            headers.remove("Range");
                        }
                    });
                }
                return plan.build();
            };
        }
    }

    private record Observation(String method, Map<String, String> headers) { }

    private static final class RawUpstream implements AutoCloseable {
        private final ServerSocket server = new ServerSocket();
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<Observation> observed = new AtomicReference<>();

        RawUpstream() throws IOException {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            workers.execute(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        workers.execute(() -> respond(socket));
                    } catch (IOException closed) {
                        if (!server.isClosed()) failure.compareAndSet(null, closed);
                    }
                }
            });
        }

        int port() { return server.getLocalPort(); }

        private void respond(Socket socket) {
            try (socket) {
                socket.setSoTimeout(5000);
                InputStream input = new BufferedInputStream(socket.getInputStream());
                String first = line(input);
                if (first == null) return;
                String[] request = first.split(" ", 3);
                Map<String, String> headers = new LinkedHashMap<>();
                for (String header; (header = line(input)) != null && !header.isEmpty(); ) {
                    int colon = header.indexOf(':');
                    headers.put(header.substring(0, colon).toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
                }
                int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                assertEquals(length, input.readNBytes(length).length);
                observed.set(new Observation(request[0], Map.copyOf(headers)));
                boolean preconditionFailed = request[0].equals("PUT") && "\"wrong\"".equals(headers.get("if-match"));
                boolean notModified = request[1].equals("/unsolicited") || (request[0].equals("GET")
                        && (headers.containsKey("if-none-match") || headers.containsKey("if-modified-since")));
                int status = preconditionFailed ? 412 : notModified ? 304 : 200;
                byte[] body = preconditionFailed ? ascii("precondition failed") : ORIGINAL;
                String metadata = preconditionFailed ? "Content-Type: application/octet-stream\r\n"
                        : "Content-Type: text/html\r\nETag: " + ORIGIN_ETAG + "\r\nLast-Modified: " + ORIGIN_MODIFIED
                        + "\r\nContent-MD5: stale\r\nDigest: SHA-256=stale\r\nContent-Digest: sha-256=:c3RhbGU=:\r\n"
                        + "Repr-Digest: sha-256=:c3RhbGU=:\r\nAccept-Ranges: bytes\r\n";
                if (request[1].equals("/quoted-cache"))
                    metadata += "Cache-Control: private=\"ETag, no-transform, X-Test\"\r\n";
                socket.getOutputStream().write(ascii("HTTP/1.1 " + status + " Fixture\r\n" + metadata
                        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n"));
                if (!request[0].equals("HEAD") && status != 304) socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            } finally { sockets.remove(socket); }
        }

        @Override public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) socket.close();
            workers.shutdownNow();
        }

        private static String line(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int value; (value = input.read()) >= 0; ) {
                if (value == '\n') return bytes.toString(StandardCharsets.ISO_8859_1).stripTrailing();
                bytes.write(value);
            }
            return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
}
