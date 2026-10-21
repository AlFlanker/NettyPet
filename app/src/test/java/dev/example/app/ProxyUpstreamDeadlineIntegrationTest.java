package dev.example.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.proxy.transport.ProxyEngine;
import dev.example.app.command.ProxyCommands;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** The upstream header deadline excludes slow uploads and response-body streaming. */
@Timeout(20)
class ProxyUpstreamDeadlineIntegrationTest {
    private static final long HEADER_TIMEOUT_MILLIS = 600;
    private static final byte[] FIRST = "first\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    void missingFinalHeadersAfterFullUploadCountAsUpstreamFailuresAndOpenCircuit() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                HttpResponse<String> response = f.client.send(f.request("/stall")
                                .POST(HttpRequest.BodyPublishers.ofString("complete request body")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(504, response.statusCode());
                assertEquals("", response.body());
                assertEquals(attempt, f.bodiesConsumed.get(), "The backend must receive the complete upload first");
                f.awaitReleased();
            }
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                assertEquals(CircuitBreaker.State.OPEN, f.commands.circuitBreaker().getState());
                assertEquals(2, f.commands.circuitBreaker().getMetrics().getNumberOfFailedCalls());
            });
            HttpResponse<String> rejected = f.client.send(f.request("/ok").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, rejected.statusCode());
            assertEquals(2, f.backendRequests.get(), "The open circuit must reject before creating upstream I/O");
            f.awaitReleased();
        }
    }

    @Test
    void finalHeadersCancelHeaderDeadlineWhileTheResponseBodyIsStillStreaming() throws Exception {
        try (Fixture f = new Fixture()) {
            HttpResponse<InputStream> response = f.client.send(f.request("/delayed-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertArrayEquals(FIRST, body.readNBytes(FIRST.length));
                var remainder = f.backendThreads.submit(body::readAllBytes);
                f.awaitBeyondHeaderDeadline();

                assertFalse(remainder.isDone(), "Receiving final headers must cancel the header timer");
                assertEquals(1, f.engine.activeRequests(), "The body must remain an active streaming exchange");
                assertEquals(0, f.commands.circuitBreaker().getMetrics().getNumberOfFailedCalls());
                f.releaseBody.countDown();
                assertEquals("last\n", new String(remainder.get(3, TimeUnit.SECONDS), StandardCharsets.US_ASCII));
            }
            f.awaitReleased();
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertEquals(1, f.commands.circuitBreaker().getMetrics().getNumberOfSuccessfulCalls()));
        }
    }

    @Test
    void slowIncomingUploadDoesNotStartTheUpstreamHeaderDeadline() throws Exception {
        try (Fixture f = new Fixture(); Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", f.port), 3000);
            socket.setSoTimeout(100);
            socket.getOutputStream().write(("POST /proxy/upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 12\r\nConnection: close\r\n\r\nhello ")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(f.firstUploadByte.await(3, TimeUnit.SECONDS), "A partial request must reach upstream");
            f.awaitBeyondHeaderDeadline();

            assertEquals(1, f.engine.activeRequests());
            assertEquals(0, f.bodiesConsumed.get());
            assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read(),
                    "Waiting for the client's remaining body must not produce an upstream timeout response");
            socket.getOutputStream().write("world!".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.setSoTimeout(3000);
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            assertEquals(12, f.uploadedBytes.get());
            f.awaitReleased();
            assertEquals(0, f.commands.circuitBreaker().getMetrics().getNumberOfFailedCalls());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final CountDownLatch releaseBody = new CountDownLatch(1);
        final CountDownLatch firstUploadByte = new CountDownLatch(1);
        final AtomicInteger backendRequests = new AtomicInteger();
        final AtomicInteger bodiesConsumed = new AtomicInteger();
        final AtomicInteger uploadedBytes = new AtomicInteger();
        final ExecutorService backendThreads = Executors.newVirtualThreadPerTaskExecutor();
        final ScheduledExecutorService gates = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "upstream-deadline-test-gates");
            thread.setDaemon(true);
            return thread;
        });
        final HttpServer backend;
        final ConfigurableApplicationContext application;
        final HttpClient client;
        final ProxyEngine engine;
        final ProxyCommands commands;
        final int port;

        Fixture() throws Exception {
            backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backend.setExecutor(backendThreads);
            backend.createContext("/", this::serve);
            backend.start();
            try {
                application = new SpringApplicationBuilder(ProxyApplication.class).run(
                        "--server.port=0", "--proxy.upstream=http://127.0.0.1:" + backend.getAddress().getPort(),
                        "--proxy.io-threads=1", "--proxy.max-concurrent=4", "--proxy.exchange-timeout=10s",
                        "--proxy.response-headers-timeout=" + HEADER_TIMEOUT_MILLIS + "ms",
                        "--proxy-command.timeout=5s", "--proxy-command.max-concurrent=4",
                        "--proxy-command.minimum-number-of-calls=2", "--proxy-command.sliding-window-size=2",
                        "--proxy-command.failure-rate-threshold=50", "--proxy-command.wait-duration-in-open-state=10s",
                        "--spring.main.banner-mode=off");
            } catch (Throwable failure) {
                backend.stop(0);
                backendThreads.shutdownNow();
                gates.shutdownNow();
                throw failure;
            }
            Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
            port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
            engine = application.getBean(ProxyEngine.class);
            commands = application.getBean("proxyCommands", ProxyCommands.class);
            client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(3)).build();
        }

        HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/proxy" + path))
                    .timeout(Duration.ofSeconds(8));
        }

        void awaitBeyondHeaderDeadline() throws InterruptedException {
            CountDownLatch elapsed = new CountDownLatch(1);
            gates.schedule(elapsed::countDown, HEADER_TIMEOUT_MILLIS + 300, TimeUnit.MILLISECONDS);
            assertTrue(elapsed.await(3, TimeUnit.SECONDS), "Test observation interval must elapse");
        }

        void awaitReleased() {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertEquals(0, engine.activeRequests());
                assertEquals(4, commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
            });
        }

        private void serve(HttpExchange exchange) throws IOException {
            try (exchange) {
                backendRequests.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                int count = 0;
                if (path.equals("/upload")) {
                    int first = exchange.getRequestBody().read();
                    if (first >= 0) count++;
                    firstUploadByte.countDown();
                }
                count += exchange.getRequestBody().readAllBytes().length;
                uploadedBytes.addAndGet(count);
                bodiesConsumed.incrementAndGet();
                if (path.equals("/stall")) {
                    awaitGate(releaseBody);
                } else if (path.equals("/delayed-stream")) {
                    CountDownLatch headersReady = new CountDownLatch(1);
                    gates.schedule(headersReady::countDown, 75, TimeUnit.MILLISECONDS);
                    awaitGate(headersReady);
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(FIRST);
                    exchange.getResponseBody().flush();
                    awaitGate(releaseBody);
                    exchange.getResponseBody().write("last\n".getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                byte[] bytes = "ok".getBytes(StandardCharsets.US_ASCII);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        private static void awaitGate(CountDownLatch gate) throws IOException {
            try {
                if (!gate.await(10, TimeUnit.SECONDS)) throw new IOException("Test backend gate timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }

        @Override public void close() {
            releaseBody.countDown();
            client.shutdownNow();
            application.close();
            backend.stop(0);
            backendThreads.shutdownNow();
            gates.shutdownNow();
        }
    }
}
