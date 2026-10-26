package dev.example.jettyclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.jettyclient.proxy.JettyProxyServlet;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Independent wire-level checks for protocol fields and case-sensitive method tokens. */
@Timeout(20)
@Execution(ExecutionMode.SAME_THREAD)
class JettyProxyProtocolTest {
    private static final Map<String, Observation> OBSERVED = new ConcurrentHashMap<>();
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger();
    private static HttpServer upstream;
    private static ExecutorService workers;
    private static ConfigurableApplicationContext application;
    private static JettyProxyServlet servlet;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(workers);
        upstream.createContext("/", JettyProxyProtocolTest::serve);
        upstream.start();
        application = new SpringApplicationBuilder(JettyClientExampleApplication.class)
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--jetty.proxy.upstream=http://127.0.0.1:" + upstream.getAddress().getPort(),
                        "--jetty.proxy.max-concurrent=1",
                        "--jetty.proxy.max-connections-per-destination=1",
                        "--jetty.proxy.exchange-timeout=5s");
        Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
        port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
        servlet = application.getBean(JettyProxyServlet.class);
    }

    @AfterEach
    void releasesAdmission() {
        await().atMost(Duration.ofSeconds(5)).until(() -> servlet.activeRequests() == 0);
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
        if (upstream != null) upstream.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NTLM dG9rZW4=", "nTlM dG9rZW4=", "NTLM", " \tNTLM\tdG9rZW4=",
            "Negotiate dG9rZW4=", "nEgOtIaTe dG9rZW4=", "Negotiate", " \tNegotiate\tdG9rZW4="})
    void rejectsConnectionBoundAuthorizationBeforeUsingSharedUpstreamConnection(String authorization) throws Exception {
        String path = newPath("connection-auth");
        WireResponse response = request("GET", path, Map.of("Authorization", authorization));
        assertAll(
                () -> assertEquals(501, response.status(), "Connection-bound credentials cannot use a shared pool"),
                () -> assertNull(OBSERVED.get(path), "The authenticated request must never reach the pooled connection"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic YWxpY2U6c2VjcmV0", "Bearer opaque-token"})
    void preservesRequestScopedAuthorization(String authorization) throws Exception {
        String path = newPath("request-auth");
        WireResponse response = request("GET", path, Map.of("Authorization", authorization));
        assertEquals(200, response.status());
        assertEquals(authorization, observed(path).authorization());
    }

    @Test
    void doesNotRejectAuthorizationRemovedByAnOriginalConnectionNomination() throws Exception {
        String path = newPath("hop-auth");
        WireResponse response = request("GET", path,
                Map.of("Connection", "close, Authorization", "Authorization", "NTLM dG9rZW4="));
        assertEquals(200, response.status());
        assertNull(observed(path).authorization(), "Hop-by-hop credentials must be removed before final auth checks");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fOo", "get", "options", "trace"})
    void preservesCaseSensitiveExtensionMethodTokens(String method) throws Exception {
        String path = newPath("method");
        WireResponse response = request(method, path, Map.of());
        assertEquals(200, response.status());
        assertEquals(method, observed(path).method(), "Method names are case-sensitive HTTP tokens");
        assertEquals("payload:" + method, response.body());
    }

    @Test
    void lowercaseHeadExtensionMethodStillCarriesItsResponseBody() throws Exception {
        String path = newPath("lowercase-head");
        WireResponse response = request("head", path, Map.of());
        assertEquals(200, response.status());
        assertEquals("head", observed(path).method());
        assertEquals("payload:head", response.body(), "Only exact HEAD has HEAD response framing semantics");
    }

    @Test
    void answersOptionsWithZeroMaxForwardsLocally() throws Exception {
        String path = newPath("options-zero");
        WireResponse response = request("OPTIONS", path, Map.of("Max-Forwards", "0"));
        assertAll(
                () -> assertEquals(200, response.status()),
                () -> assertNull(OBSERVED.get(path), "Max-Forwards: 0 prohibits forwarding OPTIONS"));
    }

    @Test
    void rejectsTraceWithZeroMaxForwardsLocally() throws Exception {
        String path = newPath("trace-zero");
        WireResponse response = request("TRACE", path, Map.of("Max-Forwards", "0"));
        assertAll(
                () -> assertEquals(501, response.status()),
                () -> assertNull(OBSERVED.get(path), "Max-Forwards: 0 prohibits forwarding TRACE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPTIONS", "TRACE"})
    void decrementsMaxForwardsWhenForwarding(String method) throws Exception {
        String path = newPath("max-forwards");
        WireResponse response = request(method, path, Map.of("Max-Forwards", "2"));
        assertEquals(200, response.status());
        assertEquals("1", observed(path).maxForwards(), "An intermediary must decrement the hop limit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "-1", "+1", "1, 2"})
    void rejectsMalformedMaxForwardsBeforeForwarding(String value) throws Exception {
        String path = newPath("malformed-max-forwards");
        WireResponse response = request("OPTIONS", path, Map.of("Max-Forwards", value));
        assertAll(
                () -> assertEquals(400, response.status()),
                () -> assertNull(OBSERVED.get(path), "Invalid hop limits must not be forwarded"));
    }

    @Test
    void acceptsTransferCodingMetadataOnHeadWithoutReadingAResponseBody() throws Exception {
        HeaderResponse response = responseFromRawUpstream("HEAD", "HTTP/1.1 200 OK\r\n"
                + "Transfer-Encoding: gzip, chunked\r\nConnection: close\r\n\r\n");
        assertEquals(200, response.status());
        assertEquals("", response.body());
        assertNull(response.header("Transfer-Encoding"), "Hop-by-hop metadata must not be copied downstream");
    }

    @Test
    void acceptsTransferCodingMetadataOnNotModifiedWithoutReadingAResponseBody() throws Exception {
        HeaderResponse response = responseFromRawUpstream("GET", "HTTP/1.1 304 Not Modified\r\n"
                + "Transfer-Encoding: gzip, chunked\r\nConnection: close\r\n\r\n");
        assertEquals(304, response.status());
        assertEquals("", response.body());
        assertNull(response.header("Transfer-Encoding"), "A 304 response has no transfer-coded message body");
    }

    @Test
    void preservesHeadContentLengthMetadataWithoutSendingResponseBytes() throws Exception {
        HeaderResponse response = responseFromRawUpstream("HEAD", "HTTP/1.1 200 OK\r\n"
                + "Content-Length: 123\r\nConnection: close\r\n\r\n");
        assertEquals(200, response.status());
        assertEquals("123", response.header("Content-Length"));
        assertEquals("", response.body(), "HEAD Content-Length describes the hypothetical GET representation");
    }

    @Test
    void rejectsPositiveContentLengthOnResetContentBeforeCommittingItsStatus() throws Exception {
        HeaderResponse response = responseFromRawUpstream("GET", "HTTP/1.1 205 Reset Content\r\n"
                + "Content-Length: 3\r\nConnection: close\r\n\r\nabc");
        assertAll(
                () -> assertEquals(502, response.status(), "A 205 response cannot declare a nonempty body"),
                () -> assertEquals("0", response.header("Content-Length")),
                () -> assertEquals("", response.body(), "Invalid upstream payload must not reach the client"));
    }

    private static HeaderResponse responseFromRawUpstream(String method, String upstreamWire) throws Exception {
        try (RawResponseUpstream raw = new RawResponseUpstream(upstreamWire);
             ConfigurableApplicationContext local = new SpringApplicationBuilder(JettyClientExampleApplication.class)
                     .run("--server.port=0", "--spring.main.banner-mode=off",
                             "--jetty.proxy.upstream=http://127.0.0.1:" + raw.port(),
                             "--jetty.proxy.max-concurrent=1", "--jetty.proxy.exchange-timeout=5s")) {
            Object webServer = local.getClass().getMethod("getWebServer").invoke(local);
            int localPort = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
            HeaderResponse result;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", localPort), 3000);
                socket.setSoTimeout(7000);
                socket.getOutputStream().write((method + " /proxy/bodyless HTTP/1.1\r\n"
                        + "Host: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
                int endHeaders = response.indexOf("\r\n\r\n");
                assertTrue(endHeaders > 0, response);
                int status = Integer.parseInt(response.substring(0, response.indexOf("\r\n")).split(" ")[1]);
                result = new HeaderResponse(status, response.substring(0, endHeaders), response.substring(endHeaders + 4));
            }
            raw.awaitRequest();
            JettyProxyServlet localServlet = local.getBean(JettyProxyServlet.class);
            await().atMost(Duration.ofSeconds(5)).until(() -> localServlet.activeRequests() == 0);
            return result;
        }
    }

    private static String newPath(String prefix) {
        return "/" + prefix + "-" + REQUEST_IDS.incrementAndGet();
    }

    private static Observation observed(String path) {
        Observation result = OBSERVED.get(path);
        assertNotNull(result, "Expected an independent upstream observation for " + path);
        return result;
    }

    private static WireResponse request(String method, String path, Map<String, String> headers) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(7000);
            StringBuilder wire = new StringBuilder(method).append(" /proxy").append(path)
                    .append(" HTTP/1.1\r\nHost: localhost\r\n");
            if (!headers.containsKey("Connection")) wire.append("Connection: close\r\n");
            headers.forEach((name, value) -> wire.append(name).append(": ").append(value).append("\r\n"));
            wire.append("\r\n");
            socket.getOutputStream().write(wire.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            int endHeaders = response.indexOf("\r\n\r\n");
            assertTrue(endHeaders > 0, response);
            int status = Integer.parseInt(response.substring(0, response.indexOf("\r\n")).split(" ")[1]);
            return new WireResponse(status, response.substring(endHeaders + 4));
        }
    }

    private static void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            String method = exchange.getRequestMethod();
            OBSERVED.put(exchange.getRequestURI().getRawPath(), new Observation(method,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Max-Forwards")));
            if (method.equals("HEAD")) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            byte[] payload = ("payload:" + method).getBytes(StandardCharsets.US_ASCII);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
        }
    }

    private record Observation(String method, String authorization, String maxForwards) { }
    private record WireResponse(int status, String body) { }

    private record HeaderResponse(int status, String headers, String body) {
        String header(String name) {
            for (String line : headers.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
            }
            return null;
        }
    }

    /** Emits exact response bytes without HttpServer validating or normalizing bodyless-response fields. */
    private static final class RawResponseUpstream implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
        private final Future<?> served;

        RawResponseUpstream(String response) throws IOException {
            listener = new ServerSocket();
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            served = worker.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    readHeaders(socket.getInputStream());
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        void awaitRequest() throws Exception { served.get(5, TimeUnit.SECONDS); }

        private static void readHeaders(InputStream input) throws IOException {
            byte[] suffix = {'\r', '\n', '\r', '\n'};
            int matched = 0;
            for (int length = 0; length < 16384; length++) {
                int value = input.read();
                if (value < 0) throw new EOFException("Proxy closed before sending request headers");
                matched = value == suffix[matched] ? matched + 1 : value == '\r' ? 1 : 0;
                if (matched == suffix.length) return;
            }
            throw new IOException("Request header limit exceeded");
        }

        @Override public void close() throws IOException {
            try { listener.close(); }
            finally { worker.shutdownNow(); }
        }
    }
}
