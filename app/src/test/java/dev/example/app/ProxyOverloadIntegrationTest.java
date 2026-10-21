package dev.example.app;

import com.sun.net.httpserver.HttpServer;
import dev.example.proxy.transport.ProxyEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.awaitility.Awaitility.await;

class ProxyOverloadIntegrationTest {
    @Test
    @Timeout(20)
    void rejectsExcessRequestsAndReleasesAdmissionAfterDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseBackend = new CountDownLatch(1);
        AtomicInteger backendRequests = new AtomicInteger();
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var backendThreads = Executors.newVirtualThreadPerTaskExecutor();
        backend.setExecutor(backendThreads);
        backend.createContext("/", exchange -> {
            try (exchange) {
                backendRequests.incrementAndGet();
                if (exchange.getRequestURI().getPath().equals("/stall")) {
                    entered.countDown();
                    try {
                        if (!releaseBackend.await(10, TimeUnit.SECONDS)) throw new IOException("Test gate timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                    return;
                }
                byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        backend.start();
        try (ConfigurableApplicationContext application = new SpringApplicationBuilder(ProxyApplication.class)
                .run("--server.port=0", "--proxy.upstream=http://127.0.0.1:" + backend.getAddress().getPort(),
                        "--proxy.max-concurrent=1", "--proxy.io-threads=1", "--proxy.exchange-timeout=2s",
                        "--spring.main.banner-mode=off")) {
            Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
            int port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
            ProxyEngine engine = application.getBean(ProxyEngine.class);
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                    .version(HttpClient.Version.HTTP_1_1).build()) {
                try {
                    var stalled = client.sendAsync(request(port, "/stall"), HttpResponse.BodyHandlers.ofString());
                    assertTrue(entered.await(3, TimeUnit.SECONDS), "First exchange must occupy admission");
                    assertEquals(1, engine.activeRequests());
                    HttpResponse<String> rejected = client.send(request(port, "/ok"), HttpResponse.BodyHandlers.ofString());
                    assertEquals(503, rejected.statusCode());
                    assertEquals(1, backendRequests.get(), "Rejected request must never reach upstream");

                    assertEquals(504, stalled.get(5, TimeUnit.SECONDS).statusCode());
                    awaitReleased(engine);
                    releaseBackend.countDown();
                    HttpResponse<String> recovered = client.send(request(port, "/ok"), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, recovered.statusCode());
                    assertEquals("ok", recovered.body());
                    assertEquals(2, backendRequests.get());
                    awaitReleased(engine);
                    assertEquals(1, engine.metrics().rejected());
                } finally {
                    releaseBackend.countDown();
                }
            }
        } finally {
            releaseBackend.countDown();
            backend.stop(0);
            backendThreads.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    void clientResetDuringIncompleteUploadClosesUpstreamAndReleasesAdmissionBeforeDeadline() throws Exception {
        CountDownLatch uploadEntered = new CountDownLatch(1);
        CountDownLatch uploadClosed = new CountDownLatch(1);
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var backendThreads = Executors.newVirtualThreadPerTaskExecutor();
        backend.setExecutor(backendThreads);
        backend.createContext("/", exchange -> {
            try (exchange) {
                if (exchange.getRequestURI().getPath().equals("/upload")) {
                    uploadEntered.countDown();
                    try {
                        exchange.getRequestBody().readAllBytes();
                    } catch (IOException cancelled) {
                        // The proxy must close its upstream socket after the downstream RST.
                    } finally {
                        uploadClosed.countDown();
                    }
                    return;
                }
                byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        backend.start();
        try (ConfigurableApplicationContext application = new SpringApplicationBuilder(ProxyApplication.class)
                .run("--server.port=0", "--proxy.upstream=http://127.0.0.1:" + backend.getAddress().getPort(),
                        "--proxy.max-concurrent=1", "--proxy.io-threads=1", "--proxy.exchange-timeout=10s",
                        "--spring.main.banner-mode=off")) {
            Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
            int port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
            ProxyEngine engine = application.getBean(ProxyEngine.class);
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.getOutputStream().write(("POST /proxy/upload HTTP/1.1\r\nHost: localhost\r\n"
                        + "Content-Length: 1000000\r\n\r\npartial-upload")
                        .getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                assertTrue(uploadEntered.await(3, TimeUnit.SECONDS), "The unfinished upload must reach upstream");
                assertEquals(1, engine.activeRequests());
                // RST makes cancellation observable while Jetty waits for the rest of the body.
                client.setSoLinger(true, 0);
            }
            assertTrue(uploadClosed.await(3, TimeUnit.SECONDS),
                    "Upstream must be closed by cancellation, before the 10-second exchange deadline");
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertEquals(0, engine.activeRequests()));
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                    .version(HttpClient.Version.HTTP_1_1).build()) {
                HttpResponse<String> recovered = client.send(request(port, "/ok"), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, recovered.statusCode());
                assertEquals("ok", recovered.body());
                awaitReleased(engine);
            }
        } finally {
            backend.stop(0);
            backendThreads.shutdownNow();
        }
    }

    private static void awaitReleased(ProxyEngine engine) {
        // Клиент может получить последний байт до завершения pool-release/cleanup.
        // Permit возвращается по этому подтверждению, а не по моменту чтения HTTP-ответа.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, engine.activeRequests()));
    }

    private static HttpRequest request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/proxy" + path))
                .timeout(Duration.ofSeconds(8)).GET().build();
    }
}
