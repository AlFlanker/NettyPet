package dev.example.app;

import dev.example.proxy.api.RequestProcessor;
import dev.example.proxy.transport.ProxyEngine;
import dev.example.app.command.ProxyCommands;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Raw upstream responses exercise framing that higher-level HTTP servers would sanitize. */
@Timeout(15)
class ProxyFailureIntegrationTest {
    private static ServerSocket backend;
    private static ServerSocket refusedPortReservation;
    private static int refusedPort;
    private static ExecutorService workers;
    private static ConfigurableApplicationContext application;
    private static HttpClient client;
    private static int port;
    private static final Map<String, CountDownLatch> bodyFailureGates = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        backend = new ServerSocket(0, 10, loopback);
        refusedPortReservation = new ServerSocket(0, 1, loopback);
        refusedPort = refusedPortReservation.getLocalPort();
        workers = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "failure-test-upstream");
            thread.setDaemon(true);
            return thread;
        });
        workers.execute(() -> {
            while (!backend.isClosed()) {
                try {
                    Socket socket = backend.accept();
                    workers.execute(() -> serve(socket));
                } catch (IOException closing) {
                    return;
                }
            }
        });
        application = new SpringApplicationBuilder(ProxyApplication.class, FailureRoutes.class)
                .run("--server.port=0", "--proxy.upstream=http://127.0.0.1:" + backend.getLocalPort(),
                        "--proxy.allowed-origins[0]=http://127.0.0.1:" + refusedPort,
                        "--proxy.io-threads=1", "--proxy.connect-timeout=1s",
                        // This fixture tests wire framing, not CircuitBreaker transitions.
                        // A larger window keeps one malformed response from changing another test's route.
                        "--proxy-command.sliding-window-size=100", "--proxy-command.minimum-number-of-calls=100",
                        "--proxy.exchange-timeout=5s", "--spring.main.banner-mode=off");
        Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
        port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterAll
    static void stop() throws IOException {
        if (application != null) application.close();
        if (client != null) client.close();
        if (backend != null) backend.close();
        if (refusedPortReservation != null) refusedPortReservation.close();
        if (workers != null) workers.shutdownNow();
    }

    @Test
    void bodylessHttp11RequestDoesNotAcquireArtificialChunkedFraming() throws Exception {
        // Unlike a high-level client, this request is guaranteed to omit both CL and TE.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /proxy/framing HTTP/1.1\r\n"
                    + "Host: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            String framing = response.substring(response.indexOf("\r\n\r\n") + 4);
            assertTrue(framing.equals("GET|none|none") || framing.equals("GET|none|0"), framing);
        }
    }

    @Test
    void rejectsUnsupportedTransferCodingBeforeCommittingStatusOrBody() throws Exception {
        HttpResponse<byte[]> response = get("/unsupported-te");
        assertEquals(502, response.statusCode());
        assertEquals(0, response.body().length);
    }

    @Test
    void rejectsTransferEncodingWithContentLengthBeforeCommit() throws Exception {
        HttpResponse<byte[]> response = get("/ambiguous-te-cl");
        assertEquals(502, response.statusCode());
        assertEquals(0, response.body().length);
        awaitReleased();
        assertEquals(200, get("/framing").statusCode());
    }

    @Test
    void rejectsEvenEqualDuplicateContentLengthsBeforeCommit() throws Exception {
        HttpResponse<byte[]> response = get("/duplicate-cl");
        assertEquals(502, response.statusCode());
        assertEquals(0, response.body().length);
        awaitReleased();
        assertEquals(200, get("/framing").statusCode());
    }

    @Test
    void oversizedUpstreamHeadersFailBeforeCommitAndReleaseAdmission() throws Exception {
        HttpResponse<byte[]> response = get("/oversized-header");
        assertEquals(502, response.statusCode());
        assertEquals(0, response.body().length);
        awaitReleased();
        assertEquals(200, get("/framing").statusCode());
    }

    @Test
    void malformedChunkAfterCommittedBodyAbortsInsteadOfSendingSuccessfulEndMarker() throws Exception {
        assertPostCommitFailure("/malformed-chunk");
    }

    @Test
    void truncatedContentLengthAfterCommittedBodyAbortsAndReleasesAdmission() throws Exception {
        assertPostCommitFailure("/truncated-length");
    }

    @Test
    void rejects205WithNonzeroContentLength() throws Exception {
        HttpResponse<byte[]> response = get("/invalid-205");
        assertEquals(502, response.statusCode());
        assertEquals(0, response.body().length);
    }

    @Test
    void accepts205WithExplicitZeroContentLength() throws Exception {
        HttpResponse<byte[]> response = get("/valid-205");
        assertEquals(205, response.statusCode());
        assertEquals(0, response.body().length);
    }

    @Test
    void rejects000ImmediatelyInsteadOfWaitingForFinalResponse() throws Exception {
        // The backend deliberately stays open. A shorter client deadline ensures that
        // 000 cannot pass this test by eventually becoming a proxy timeout or socket EOF.
        HttpResponse<byte[]> response = get("/invalid-status-000", Duration.ofSeconds(2));
        assertEquals(502, response.statusCode());
        assertEquals(0, response.body().length);
    }

    @Test
    void rejects600BeforeForwardingInvalidStatusToClient() throws Exception {
        HttpResponse<byte[]> response = get("/invalid-status-600");
        assertEquals(502, response.statusCode());
        assertEquals(0, response.body().length);
    }

    @Test
    void refusedUpstreamConnectionIsBadGatewayNotCapacityExhaustion() throws Exception {
        // Reserve until the app has chosen its port; then connect immediately to the closed port.
        refusedPortReservation.close();
        HttpResponse<byte[]> response = get("/connect-refused");
        assertEquals(502, response.statusCode());
        assertEquals(0, response.body().length);
    }

    private static HttpResponse<byte[]> get(String path) throws IOException, InterruptedException {
        return get(path, Duration.ofSeconds(8));
    }

    private static HttpResponse<byte[]> get(String path, Duration timeout) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/proxy" + path))
                        .timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assertPostCommitFailure(String path) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        bodyFailureGates.put(path, release);
        try {
            HttpResponse<java.io.InputStream> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/proxy" + path))
                            .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                assertEquals(200, response.statusCode());
                assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), body.readNBytes(5));
                // A real client has received the prefix: the response is irrevocably committed.
                // Only now may the raw backend send malformed framing or truncate its body.
                release.countDown();
                assertThrows(IOException.class, body::readAllBytes,
                        "An incomplete response must fail, not look like a successfully finished body");
            }
            awaitReleased();
            HttpResponse<byte[]> recovered = get("/framing");
            assertEquals(200, recovered.statusCode());
            assertTrue(new String(recovered.body(), StandardCharsets.US_ASCII).startsWith("GET|"));
        } finally {
            release.countDown();
            bodyFailureGates.remove(path);
        }
    }

    private static void awaitReleased() {
        ProxyEngine engine = application.getBean(ProxyEngine.class);
        ProxyCommands commands = application.getBean("proxyCommands", ProxyCommands.class);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertEquals(0, engine.activeRequests());
            assertEquals(commands.bulkhead().getBulkheadConfig().getMaxConcurrentCalls(),
                    commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
        });
    }

    private static void serve(Socket accepted) {
        try (Socket socket = accepted) {
            socket.setSoTimeout(5000);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = input.readLine();
            if (requestLine == null) return;
            Map<String, String> headers = new HashMap<>();
            for (String line; (line = input.readLine()) != null && !line.isEmpty(); ) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            String response;
            String path = requestLine.split(" ")[1];
            switch (path) {
                case "/framing" -> {
                    String body = requestLine.split(" ")[0] + "|" + headers.getOrDefault("transfer-encoding", "none")
                            + "|" + headers.getOrDefault("content-length", "none");
                    response = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
                            + "\r\nConnection: close\r\n\r\n" + body;
                }
                case "/unsupported-te" -> response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n"
                        + "Connection: close\r\n\r\n1\r\nx\r\n0\r\n\r\n";
                case "/ambiguous-te-cl" -> response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n"
                        + "Content-Length: 5\r\nConnection: close\r\n\r\n5\r\nhello\r\n0\r\n\r\n";
                case "/duplicate-cl" -> response = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n"
                        + "Content-Length: 5\r\nConnection: close\r\n\r\nhello";
                case "/oversized-header" -> response = "HTTP/1.1 200 OK\r\nX-Oversized: "
                        + "a".repeat(17 * 1024) + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                case "/malformed-chunk" -> response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n5\r\nhello\r\n";
                case "/truncated-length" -> response = "HTTP/1.1 200 OK\r\nContent-Length: 20\r\n"
                        + "Connection: close\r\n\r\nhello";
                case "/invalid-205" -> response = "HTTP/1.1 205 Reset Content\r\nContent-Length: 1\r\n"
                        + "Connection: close\r\n\r\nx";
                case "/valid-205" -> response = "HTTP/1.1 205 Reset Content\r\nContent-Length: 0\r\n"
                        + "Connection: close\r\n\r\n";
                case "/invalid-status-000" -> response = "HTTP/1.1 000 Invalid\r\nContent-Length: 0\r\n\r\n";
                case "/invalid-status-600" -> response = "HTTP/1.1 600 Invalid\r\nContent-Length: 0\r\n"
                        + "Connection: close\r\n\r\n";
                default -> response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            }
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            CountDownLatch gate = bodyFailureGates.get(path);
            if (gate != null) {
                if (!gate.await(5, TimeUnit.SECONDS)) throw new IOException("Client did not confirm the body prefix");
                if (path.equals("/malformed-chunk")) {
                    socket.getOutputStream().write("G\r\nx\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                // /truncated-length closes with only five of the declared twenty bytes sent.
            }
            if (path.equals("/invalid-status-000")) input.read(); // Wait until the proxy rejects and closes.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Proxy rejection and teardown can close the socket while the test peer is writing.
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureRoutes {
        @Bean
        RequestProcessor failureRoutes() {
            return (incoming, outgoing, chain) -> {
                if (incoming.rawPath().equals("/connect-refused")) {
                    outgoing.uri(URI.create("http://127.0.0.1:" + refusedPort + "/"));
                }
                chain.proceed();
            };
        }
    }
}
