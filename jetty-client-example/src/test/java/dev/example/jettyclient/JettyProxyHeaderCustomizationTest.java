package dev.example.jettyclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.jettyclient.proxy.JettyProxyServlet;
import dev.example.jettyclient.proxy.ProxyHeaderCustomizer;
import dev.example.jettyclient.proxy.ProxyHeaderEditor;
import dev.example.jettyclient.proxy.ProxyRequestMetadata;
import java.io.IOException;
import java.net.InetSocketAddress;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the public bean extension contract against actual upstream/downstream HTTP. */
@Timeout(20)
@Execution(ExecutionMode.SAME_THREAD)
class JettyProxyHeaderCustomizationTest {
    private static final ConcurrentLinkedQueue<String> CALLS = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<ProxyRequestMetadata> METADATA = new AtomicReference<>();
    private static final AtomicReference<List<String>> EDITOR_VALUES = new AtomicReference<>();
    private static final AtomicReference<Map<String, List<String>>> RECEIVED = new AtomicReference<>();
    private static final AtomicInteger UPSTREAM_REQUESTS = new AtomicInteger();
    private static HttpServer upstream;
    private static ExecutorService upstreamWorkers;
    private static ConfigurableApplicationContext application;
    private static JettyProxyServlet servlet;
    private static HttpClient client;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamWorkers = Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(upstreamWorkers);
        upstream.createContext("/", JettyProxyHeaderCustomizationTest::serve);
        upstream.start();
        application = new SpringApplicationBuilder(JettyClientExampleApplication.class, HeaderBeans.class)
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--example.proxy.upstream=http://127.0.0.1:" + upstream.getAddress().getPort(),
                        "--example.proxy.max-concurrent=1", "--example.proxy.exchange-timeout=5s");
        Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
        port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
        servlet = application.getBean(JettyProxyServlet.class);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    @BeforeEach
    void clearObservations() {
        CALLS.clear();
        METADATA.set(null);
        EDITOR_VALUES.set(null);
        RECEIVED.set(null);
    }

    @AfterEach
    void releasesAdmission() {
        awaitReleased();
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
        if (client != null) client.close();
        if (upstream != null) upstream.stop(0);
        if (upstreamWorkers != null) upstreamWorkers.shutdownNow();
    }

    @Test
    void invokesOrderedBeansOncePerPhaseAndPreservesRepeatedAndUnchangedHeaders() throws Exception {
        HttpResponse<byte[]> response = get("/ordered");
        assertEquals(200, response.statusCode());
        assertEquals("payload", new String(response.body(), StandardCharsets.US_ASCII));
        assertEquals(List.of("request:first", "request:second", "response:first", "response:second"),
                new ArrayList<>(CALLS));
        assertEquals(List.of("first", "second"), RECEIVED.get().get("x-order"));
        assertEquals(List.of("customized"), RECEIVED.get().get("x-replace"));
        assertFalse(RECEIVED.get().containsKey("x-remove"));
        assertEquals(List.of("caller-value"), RECEIVED.get().get("x-caller"));
        assertEquals(List.of("first", "second"), response.headers().allValues("X-Order"));
        assertEquals("upstream-value", response.headers().firstValue("X-Upstream").orElseThrow());
        assertFalse(response.headers().firstValue("X-Remove-Response").isPresent());
        assertEquals(List.of("a=1; Path=/", "b=2; Path=/", "custom=3; Path=/"),
                response.headers().allValues("Set-Cookie"));
        assertTrue(response.headers().allValues("X-Response-Secret").isEmpty(),
                "An original Connection nomination must survive hook attempts to re-add its field");

        ProxyRequestMetadata metadata = METADATA.get();
        assertEquals("GET", metadata.method());
        assertEquals("/ordered", metadata.target().getRawPath());
        assertEquals(List.of("caller-value"), metadata.headers().get("x-caller"));
        assertThrows(UnsupportedOperationException.class, () -> metadata.headers().put("x-added", List.of("no")));
        assertThrows(UnsupportedOperationException.class, () -> metadata.headers().get("x-caller").add("no"));
        assertThrows(UnsupportedOperationException.class, () -> EDITOR_VALUES.get().add("no"));
    }

    @Test
    void finalSanitizationRemovesOriginallyConnectionNominatedRequestHeaderEvenAfterHookAddsIt() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /proxy/nominated HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close, X-Request-Secret\r\nX-Request-Secret: original-secret\r\n"
                    + "X-Caller: caller-value\r\nX-Remove: remove-me\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
        }
        assertFalse(RECEIVED.get().containsKey("x-request-secret"));
        assertEquals(List.of("caller-value"), RECEIVED.get().get("x-caller"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Host", "Content-Length", "Connection", "Transfer-Encoding", "Expect", "Content-Type"})
    void rejectsProtectedRequestMutationBeforeUpstreamAndReleasesAdmission(String header) throws Exception {
        int before = UPSTREAM_REQUESTS.get();
        HttpResponse<byte[]> response = get("/reject-request/" + header);
        assertEquals(500, response.statusCode());
        assertEquals(0, response.body().length);
        assertEquals(before, UPSTREAM_REQUESTS.get(), "Invalid request customization must not send an upstream request");
        assertRecovers();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Content-Length", "Connection", "Content-Encoding", "Content-Range"})
    void rejectsProtectedResponseMutationBeforeCommitAndReleasesAdmission(String header) throws Exception {
        int before = UPSTREAM_REQUESTS.get();
        HttpResponse<byte[]> response = get("/reject-response/" + header);
        assertEquals(500, response.statusCode());
        assertEquals(0, response.body().length, "An invalid response hook must not leak the upstream body");
        assertTrue(response.headers().allValues("X-Upstream").isEmpty(), "Failed response headers must not be committed");
        assertEquals(before + 1, UPSTREAM_REQUESTS.get());
        assertRecovers();
    }

    @Test
    void rejectsCrlfValuesInBothPhasesAndRecovers() throws Exception {
        for (String phase : List.of("request", "response")) {
            int before = UPSTREAM_REQUESTS.get();
            HttpResponse<byte[]> response = get("/invalid-" + phase);
            assertEquals(500, response.statusCode());
            assertEquals(0, response.body().length);
            assertEquals(before + (phase.equals("response") ? 1 : 0), UPSTREAM_REQUESTS.get());
            assertRecovers();
        }
    }

    @Test
    void customizerExceptionsInBothPhasesReleaseAdmissionAndAllowRecovery() throws Exception {
        for (String phase : List.of("request", "response")) {
            int before = UPSTREAM_REQUESTS.get();
            HttpResponse<byte[]> response = get("/throw-" + phase);
            assertEquals(500, response.statusCode());
            assertEquals(0, response.body().length);
            assertEquals(before + (phase.equals("response") ? 1 : 0), UPSTREAM_REQUESTS.get());
            assertRecovers();
        }
    }

    private static HttpResponse<byte[]> get(String suffix) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/proxy" + suffix))
                        .header("X-Caller", "caller-value").header("X-Remove", "remove-me")
                        .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assertRecovers() throws Exception {
        awaitReleased();
        HttpResponse<byte[]> recovered = get("/recovered");
        assertEquals(200, recovered.statusCode());
        assertEquals("payload", new String(recovered.body(), StandardCharsets.US_ASCII));
        awaitReleased();
    }

    private static void awaitReleased() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, servlet.activeRequests()));
    }

    private static void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            UPSTREAM_REQUESTS.incrementAndGet();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            RECEIVED.set(headers);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Set-Cookie", "a=1; Path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "b=2; Path=/");
            exchange.getResponseHeaders().add("X-Upstream", "upstream-value");
            exchange.getResponseHeaders().add("X-Remove-Response", "remove-me");
            exchange.getResponseHeaders().add("Connection", "close, X-Response-Secret");
            exchange.getResponseHeaders().add("X-Response-Secret", "original-secret");
            byte[] body = "payload".getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HeaderBeans {
        // Declaration order deliberately differs from execution order.
        @Bean
        @Order(20)
        ProxyHeaderCustomizer secondHeaders() {
            return new ProxyHeaderCustomizer() {
                @Override public void customizeRequest(ProxyRequestMetadata metadata, ProxyHeaderEditor headers) {
                    CALLS.add("request:second");
                    EDITOR_VALUES.set(headers.values("X-Order"));
                    headers.add("X-Order", "second");
                }

                @Override public void customizeResponse(ProxyRequestMetadata metadata, int status, ProxyHeaderEditor headers) {
                    CALLS.add("response:second");
                    headers.add("X-Order", "second");
                    headers.add("Set-Cookie", "custom=3; Path=/");
                }
            };
        }

        @Bean
        @Order(10)
        ProxyHeaderCustomizer firstHeaders() {
            return new ProxyHeaderCustomizer() {
                @Override public void customizeRequest(ProxyRequestMetadata metadata, ProxyHeaderEditor headers) {
                    CALLS.add("request:first");
                    METADATA.set(metadata);
                    String path = metadata.target().getRawPath();
                    if (path.startsWith("/reject-request/")) {
                        headers.set(path.substring("/reject-request/".length()), "forged");
                    } else if (path.equals("/invalid-request")) {
                        headers.add("X-Invalid", "bad\r\nInjected: yes");
                    } else if (path.equals("/throw-request")) {
                        throw new IllegalStateException("Request customization failed");
                    }
                    headers.add("X-Order", "first");
                    headers.set("X-Replace", "customized");
                    headers.remove("X-Remove");
                    headers.add("X-Request-Secret", "hook-secret");
                }

                @Override public void customizeResponse(ProxyRequestMetadata metadata, int status, ProxyHeaderEditor headers) {
                    CALLS.add("response:first");
                    String path = metadata.target().getRawPath();
                    if (path.startsWith("/reject-response/")) {
                        headers.set(path.substring("/reject-response/".length()), "forged");
                    } else if (path.equals("/invalid-response")) {
                        headers.add("X-Invalid", "bad\r\nInjected: yes");
                    } else if (path.equals("/throw-response")) {
                        throw new IllegalStateException("Response customization failed");
                    }
                    headers.add("X-Order", "first");
                    headers.remove("X-Remove-Response");
                    headers.add("X-Response-Secret", "hook-secret");
                }
            };
        }
    }
}
