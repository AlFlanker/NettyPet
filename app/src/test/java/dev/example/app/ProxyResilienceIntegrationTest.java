package dev.example.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.proxy.transport.ProxyEngine;
import dev.example.resilience.CommandExecutor;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Real sockets verify that resilience owns the entire streaming exchange, including cancellation. */
@Timeout(20)
class ProxyResilienceIntegrationTest {
    private static final byte[] FIRST = "first\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    void bulkheadRejectsWithoutUpstreamIoAndTimeLimiterReleasesBothAdmissions() throws Exception {
        try (Fixture f = new Fixture("--proxy-command.max-concurrent=1", "--proxy-command.timeout=1s")) {
            var stalled = f.client.sendAsync(f.request("/stall"), HttpResponse.BodyHandlers.ofString());
            assertTrue(f.stallEntered.await(3, TimeUnit.SECONDS));
            assertEquals(1, f.engine.activeRequests());
            assertEquals(0, f.commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
            assertEquals(503, f.get("/ok").statusCode());
            assertEquals(1, f.backendRequests.get(), "Bulkhead rejection must not reach upstream");

            assertEquals(504, stalled.get(4, TimeUnit.SECONDS).statusCode());
            f.awaitReleased();
            f.releaseStall.countDown();
            HttpResponse<String> recovered = f.get("/ok");
            assertEquals(200, recovered.statusCode());
            assertEquals("ok", recovered.body());
            assertEquals(2, f.backendRequests.get());
            f.awaitReleased();
        }
    }

    @Test
    void repeatedUpstreamServerErrorsOpenCircuitAndBlockTheNextNetworkRequest() throws Exception {
        try (Fixture f = new Fixture("--proxy-command.wait-duration-in-open-state=10s")) {
            f.upstreamStatus.set(500);
            assertEquals(500, f.get("/status").statusCode());
            f.awaitReleased();
            assertEquals(500, f.get("/status").statusCode());
            f.awaitReleased();
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertEquals(CircuitBreaker.State.OPEN, f.commands.circuitBreaker().getState()));
            assertEquals(503, f.get("/ok").statusCode());
            assertEquals(2, f.backendRequests.get(), "An open circuit must not invoke the proxy engine");
            f.awaitReleased();
        }
    }

    @Test
    void upstreamClientErrorsDoNotOpenCircuit() throws Exception {
        try (Fixture f = new Fixture()) {
            f.upstreamStatus.set(400);
            for (int request = 0; request < 3; request++) {
                assertEquals(400, f.get("/status").statusCode());
                f.awaitReleased();
            }
            assertEquals(200, f.get("/ok").statusCode());
            assertEquals(4, f.backendRequests.get());
            f.awaitReleased();
            assertEquals(CircuitBreaker.State.CLOSED, f.commands.circuitBreaker().getState());
        }
    }

    @Test
    void successfulHalfOpenProbeClosesCircuitAfterItsWaitDuration() throws Exception {
        try (Fixture f = new Fixture("--proxy-command.wait-duration-in-open-state=200ms",
                "--proxy-command.permitted-calls-in-half-open-state=1")) {
            f.upstreamStatus.set(500);
            assertEquals(500, f.get("/status").statusCode());
            f.awaitReleased();
            assertEquals(500, f.get("/status").statusCode());
            f.awaitReleased();
            f.upstreamStatus.set(200);

            // Permission requests drive OPEN -> HALF_OPEN when automatic transition is disabled.
            await().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(3)).until(() -> {
                int status = f.get("/status").statusCode();
                assertTrue(status == 503 || status == 200, "Unexpected probe status: " + status);
                return status == 200;
            });
            f.awaitReleased();
            assertEquals(3, f.backendRequests.get(), "Only the permitted half-open probe may reach upstream");
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertEquals(CircuitBreaker.State.CLOSED, f.commands.circuitBreaker().getState()));
            assertEquals(200, f.get("/ok").statusCode());
            assertEquals(4, f.backendRequests.get());
            f.awaitReleased();
        }
    }

    @Test
    void timeLimiterAbortsCommittedStreamInsteadOfSendingASuccessfulEndMarker() throws Exception {
        try (Fixture f = new Fixture("--proxy-command.timeout=1s", "--proxy-command.max-concurrent=1")) {
            HttpResponse<InputStream> response = f.client.send(f.request("/stream"), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertArrayEquals(FIRST, body.readNBytes(FIRST.length));
                var remainder = f.backendThreads.submit(body::readAllBytes);
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> remainder.get(4, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, failure.getCause(),
                        "A truncated committed response must fail body consumption rather than end normally");
            }
            f.awaitReleased();
            f.releaseStream.countDown();
            assertEquals(200, f.get("/ok").statusCode());
            f.awaitReleased();
        }
    }

    @Test
    void receivingHeadersDoesNotCompleteCommandOrReleaseStreamingBulkheadPermit() throws Exception {
        try (Fixture f = new Fixture("--proxy-command.max-concurrent=1")) {
            HttpResponse<InputStream> response = f.client.send(f.request("/stream"), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertArrayEquals(FIRST, body.readNBytes(FIRST.length));
                assertEquals(1, f.engine.activeRequests());
                assertEquals(0, f.commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
                assertEquals(503, f.get("/ok").statusCode());
                assertEquals(1, f.backendRequests.get());
                f.releaseStream.countDown();
                assertEquals("last\n", new String(body.readAllBytes(), StandardCharsets.US_ASCII));
            }
            f.awaitReleased();
            assertEquals(200, f.get("/ok").statusCode());
            assertEquals(2, f.backendRequests.get());
            f.awaitReleased();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final CountDownLatch stallEntered = new CountDownLatch(1);
        final CountDownLatch releaseStall = new CountDownLatch(1);
        final CountDownLatch releaseStream = new CountDownLatch(1);
        final AtomicInteger backendRequests = new AtomicInteger();
        final AtomicInteger upstreamStatus = new AtomicInteger(200);
        final ExecutorService backendThreads = Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer backend;
        final ConfigurableApplicationContext application;
        final HttpClient client;
        final ProxyEngine engine;
        final CommandExecutor<?> commands;
        final int port;

        Fixture(String... overrides) throws Exception {
            backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backend.setExecutor(backendThreads);
            backend.createContext("/", this::serve);
            backend.start();
            List<String> arguments = new ArrayList<>(List.of("--server.port=0",
                    "--proxy.upstream=http://127.0.0.1:" + backend.getAddress().getPort(),
                    "--proxy.io-threads=1", "--proxy.max-concurrent=8", "--proxy.exchange-timeout=10s",
                    "--proxy-command.timeout=5s", "--proxy-command.max-concurrent=8",
                    "--proxy-command.minimum-number-of-calls=2", "--proxy-command.sliding-window-size=2",
                    "--proxy-command.failure-rate-threshold=50", "--spring.main.banner-mode=off"));
            // Spring command-line properties accumulate duplicate values, so replace each default explicitly.
            for (String override : overrides) {
                String key = override.substring(0, override.indexOf('=') + 1);
                arguments.removeIf(argument -> argument.startsWith(key));
                arguments.add(override);
            }
            try {
                application = new SpringApplicationBuilder(ProxyApplication.class).run(arguments.toArray(String[]::new));
            } catch (Throwable failure) {
                backend.stop(0);
                backendThreads.shutdownNow();
                throw failure;
            }
            Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
            port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
            engine = application.getBean(ProxyEngine.class);
            commands = application.getBean("proxyCommands", CommandExecutor.class);
            client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(3)).build();
        }

        HttpRequest request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/proxy" + path))
                    .timeout(Duration.ofSeconds(8)).GET().build();
        }

        HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return client.send(request(path), HttpResponse.BodyHandlers.ofString());
        }

        void awaitReleased() {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertEquals(0, engine.activeRequests());
                assertEquals(commands.bulkhead().getBulkheadConfig().getMaxConcurrentCalls(),
                        commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
            });
        }

        private void serve(HttpExchange exchange) throws IOException {
            try (exchange) {
                backendRequests.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/stall")) {
                    stallEntered.countDown();
                    awaitGate(releaseStall);
                } else if (path.equals("/stream")) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(FIRST);
                    exchange.getResponseBody().flush();
                    awaitGate(releaseStream);
                    exchange.getResponseBody().write("last\n".getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                int status = path.equals("/status") ? upstreamStatus.get() : 200;
                byte[] bytes = "ok".getBytes(StandardCharsets.US_ASCII);
                exchange.sendResponseHeaders(status, bytes.length);
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

        @Override
        public void close() {
            releaseStall.countDown();
            releaseStream.countDown();
            client.shutdownNow();
            application.close();
            backend.stop(0);
            backendThreads.shutdownNow();
        }
    }
}
