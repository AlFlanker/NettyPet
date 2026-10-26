package dev.example.jettyclient;

import dev.example.jettyclient.proxy.JettyProxyServlet;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

/** Uses bounded wire fixtures to verify parser limits before and after downstream commit. */
@Timeout(20)
@Execution(ExecutionMode.SAME_THREAD)
class JettyProxyResponseLimitsTest {
    private static final int HEADER_LIMIT = 4096;
    private static final String OVERSIZED = "a".repeat(HEADER_LIMIT * 2);
    private static final byte[] PREFIX = "first".getBytes(StandardCharsets.US_ASCII);
    private static RawUpstream upstream;
    private static ConfigurableApplicationContext application;
    private static HttpClient client;
    private static JettyProxyServlet servlet;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        upstream = new RawUpstream();
        application = new SpringApplicationBuilder(JettyClientExampleApplication.class)
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--jetty.proxy.upstream=http://127.0.0.1:" + upstream.port(),
                        "--jetty.proxy.max-response-headers-bytes=" + HEADER_LIMIT,
                        "--jetty.proxy.max-concurrent=1", "--jetty.proxy.exchange-timeout=30s");
        Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
        port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
        servlet = application.getBean(JettyProxyServlet.class);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void releasesAdmissionAndChecksFixture() {
        upstream.releaseMetadata.countDown();
        awaitReleased();
        assertNull(upstream.failure.get(), "The wire fixture must complete correctly");
    }

    @AfterAll
    static void stop() throws Exception {
        if (upstream != null) upstream.releaseMetadata.countDown();
        if (application != null) application.close();
        if (client != null) client.close();
        if (upstream != null) upstream.close();
    }

    @Test
    void rejectsOversizedUpstreamHeaderEvenWhenSanitizationWouldRemoveIt() throws Exception {
        HttpResponse<byte[]> response = client.send(request("/oversized-headers").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(502, response.statusCode());
        assertEquals(0, response.body().length);
        assertRecovers();
    }

    @Test
    void oversizedTrailersAbortCommittedResponseInsteadOfSuccessfulEof() throws Exception {
        assertCommittedMetadataRejected("/oversized-trailers");
    }

    @Test
    void oversizedChunkExtensionAbortsCommittedResponseInsteadOfSuccessfulEof() throws Exception {
        assertCommittedMetadataRejected("/oversized-extension");
    }

    private static void assertCommittedMetadataRejected(String path) throws Exception {
        upstream.releaseMetadata = new CountDownLatch(1);
        try {
            HttpResponse<InputStream> response = client.send(request(path).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertArrayEquals(PREFIX, body.readNBytes(PREFIX.length));
                // The response is committed; the later parser failure must truncate its HTTP framing.
                upstream.releaseMetadata.countDown();
                assertThrows(IOException.class, body::readAllBytes);
            }
        } finally {
            upstream.releaseMetadata.countDown();
        }
        assertRecovers();
    }

    private static void assertRecovers() throws Exception {
        awaitReleased();
        HttpResponse<String> response = client.send(request("/ok").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
        awaitReleased();
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/proxy" + path))
                .timeout(Duration.ofSeconds(25));
    }

    private static void awaitReleased() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, servlet.activeRequests()));
    }

    private static final class RawUpstream implements AutoCloseable {
        final ServerSocket listener = new ServerSocket();
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile CountDownLatch releaseMetadata = new CountDownLatch(0);

        RawUpstream() throws IOException {
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
            boolean oversized = false;
            try (socket) {
                socket.setSoTimeout(8000);
                InputStream input = new BufferedInputStream(socket.getInputStream());
                String first = line(input);
                if (first == null) return;
                String path = first.split(" ", 3)[1];
                for (String header; (header = line(input)) != null && !header.isEmpty(); ) { }
                oversized = path.startsWith("/oversized-");
                switch (path) {
                    case "/oversized-headers" -> write(socket,
                            "HTTP/1.1 200 OK\r\nConnection: close, X-Large\r\nX-Large: " + OVERSIZED
                                    + "\r\nContent-Length: 2\r\n\r\nok");
                    case "/oversized-trailers", "/oversized-extension" -> {
                        write(socket, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                                + "5\r\nfirst\r\n");
                        if (!releaseMetadata.await(8, TimeUnit.SECONDS)) throw new IOException("Metadata gate timed out");
                        // Both variants are complete valid wire messages when no size limit is imposed.
                        if (path.equals("/oversized-trailers")) {
                            write(socket, "0\r\nX-Large: " + OVERSIZED + "\r\n\r\n");
                        } else {
                            write(socket, "1;ignore=" + OVERSIZED + "\r\nZ\r\n0\r\n\r\n");
                        }
                    }
                    default -> write(socket, "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 2\r\n\r\nok");
                }
            } catch (Throwable unexpected) {
                if (!(oversized && unexpected instanceof SocketException)) failure.compareAndSet(null, unexpected);
            } finally {
                sockets.remove(socket);
            }
        }

        private static String line(InputStream input) throws IOException {
            StringBuilder value = new StringBuilder();
            while (value.length() < 16 * 1024) {
                int next = input.read();
                if (next == -1) return value.isEmpty() ? null : value.toString();
                if (next == '\n') {
                    if (value.isEmpty() || value.charAt(value.length() - 1) != '\r') throw new IOException("Missing CRLF");
                    return value.substring(0, value.length() - 1);
                }
                value.append((char) next);
            }
            throw new IOException("Fixture request line too long");
        }

        private static void write(Socket socket, String value) throws IOException {
            socket.getOutputStream().write(value.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        }

        @Override public void close() throws Exception {
            releaseMetadata.countDown();
            listener.close();
            for (Socket socket : sockets) socket.close();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
