package dev.example.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.proxy.transport.ProxyEngine;
import dev.example.resilience.CommandExecutor;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Real sockets cover the native writer and its conservative Servlet-wrapper fallback. */
@Timeout(25)
class ProxyOptimizedIoIntegrationTest {
    private static final int BLOCK = 8192;
    private static final int LARGE_BODY = 8 * 1024 * 1024;
    private static final int RECOVERY_BODY = 512 * 1024 + 37;
    private static final int WRAPPER_XOR = 0x5a;

    @Test
    void resetOfNonReadingClientReleasesBothAdmissionsAndNextBinaryResponseIsIntact() throws Exception {
        try (Fixture f = new Fixture()) {
            try (Socket stalled = new Socket()) {
                stalled.setReceiveBufferSize(1024);
                stalled.connect(new InetSocketAddress("127.0.0.1", f.port), 3000);
                stalled.setSoTimeout(3000);
                stalled.getOutputStream().write(("GET /proxy/large HTTP/1.1\r\nHost: localhost\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                stalled.getOutputStream().flush();
                assertTrue(readHeaders(stalled.getInputStream()).startsWith("HTTP/1.1 200"));
                assertEquals(Byte.toUnsignedInt(pattern(0, 11)), stalled.getInputStream().read());

                // Stop consuming downstream while the upstream attempts a bounded multi-MiB stream.
                // Exact kernel-buffer occupancy is intentionally not an assertion: deterministic
                // pending-write/ref-count tests cover that ownership boundary separately.
                f.sendRemaining.countDown();
                assertTrue(f.largeTransferStarted.await(3, TimeUnit.SECONDS));
                await().during(Duration.ofMillis(250)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                    assertEquals(1, f.engine.activeRequests());
                    assertEquals(0, f.commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
                });
                stalled.setSoLinger(true, 0);
            }
            f.finishLarge.countDown();
            f.awaitReleased();
            assertTrue(f.largeHandlerFinished.await(3, TimeUnit.SECONDS), "Cancellation must unblock the upstream writer");

            // A different pattern detects stale/prematurely-recycled content from the aborted call.
            HttpResponse<InputStream> recovered = f.client.send(f.request("/binary"),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, recovered.statusCode());
            try (InputStream body = recovered.body()) { assertPattern(body, RECOVERY_BODY, 93, 0); }
            f.awaitReleased();
        }
    }

    @Test
    void responseWrapperStillTransformsEveryByteInsteadOfBeingBypassedByNativeWriter() throws Exception {
        try (Fixture f = new Fixture()) {
            HttpResponse<InputStream> response = f.client.send(f.request("/wrapped"),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            assertEquals("xor", response.headers().firstValue("X-Test-Wrapper").orElseThrow());
            try (InputStream body = response.body()) { assertPattern(body, RECOVERY_BODY, 93, WRAPPER_XOR); }
            f.awaitReleased();
        }
    }

    private static byte pattern(int offset, int seed) {
        return (byte) ((offset * 31) ^ (offset >>> 8) ^ seed);
    }

    private static void fill(byte[] bytes, int offset, int count, int seed, int xor) {
        for (int i = 0; i < count; i++) bytes[i] = (byte) (pattern(offset + i, seed) ^ xor);
    }

    private static void assertPattern(InputStream input, int length, int seed, int xor) throws IOException {
        byte[] actual = new byte[BLOCK];
        byte[] expected = new byte[BLOCK];
        int offset = 0;
        while (offset < length) {
            int count = Math.min(BLOCK, length - offset);
            assertEquals(count, input.readNBytes(actual, 0, count), "Incomplete response body");
            fill(expected, offset, count, seed, xor);
            assertArrayEquals(Arrays.copyOf(expected, count), Arrays.copyOf(actual, count),
                    "Unexpected bytes at offset " + offset);
            offset += count;
        }
        assertEquals(-1, input.read(), "Unexpected bytes after the complete response");
    }

    private static String readHeaders(InputStream input) throws IOException {
        StringBuilder headers = new StringBuilder();
        while (headers.length() < 16384) {
            int next = input.read();
            if (next < 0) throw new IOException("EOF before response headers");
            headers.append((char) next);
            int size = headers.length();
            if (size >= 4 && headers.substring(size - 4).equals("\r\n\r\n")) return headers.toString();
        }
        throw new IOException("Unexpectedly large test response headers");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WrapperConfiguration {
        @Bean
        FilterRegistrationBean<Filter> transformWrappedResponse() {
            Filter filter = (request, response, chain) -> {
                if (((HttpServletRequest) request).getRequestURI().equals("/proxy/wrapped")) {
                    var wrapped = new HttpServletResponseWrapper((HttpServletResponse) response) {
                        private ServletOutputStream stream;

                        @Override public ServletOutputStream getOutputStream() throws IOException {
                            if (stream == null) {
                                ServletOutputStream delegate = super.getOutputStream();
                                stream = new ServletOutputStream() {
                                    @Override public boolean isReady() { return delegate.isReady(); }
                                    @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                                    @Override public void write(int value) throws IOException { delegate.write(value ^ WRAPPER_XOR); }
                                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                                        // A wrapper owns its transformation buffer until the delegate's
                                        // write completes; do not mutate the proxy's supplied array.
                                        byte[] transformed = new byte[length];
                                        for (int i = 0; i < length; i++) transformed[i] = (byte) (bytes[offset + i] ^ WRAPPER_XOR);
                                        delegate.write(transformed);
                                    }
                                    @Override public void flush() throws IOException { delegate.flush(); }
                                    @Override public void close() throws IOException { delegate.close(); }
                                };
                            }
                            return stream;
                        }
                    };
                    wrapped.setHeader("X-Test-Wrapper", "xor");
                    chain.doFilter(request, wrapped);
                } else chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setName("testResponseTransformer");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
            registration.setAsyncSupported(true);
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final CountDownLatch sendRemaining = new CountDownLatch(1);
        final CountDownLatch finishLarge = new CountDownLatch(1);
        final CountDownLatch largeTransferStarted = new CountDownLatch(1);
        final CountDownLatch largeHandlerFinished = new CountDownLatch(1);
        final ExecutorService backendThreads = Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer backend;
        final ConfigurableApplicationContext application;
        final HttpClient client;
        final ProxyEngine engine;
        final CommandExecutor<?> commands;
        final int port;

        Fixture() throws Exception {
            backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backend.setExecutor(backendThreads);
            backend.createContext("/", this::serve);
            backend.start();
            try {
                application = new SpringApplicationBuilder(ProxyApplication.class, WrapperConfiguration.class).run(
                        "--server.port=0", "--proxy.upstream=http://127.0.0.1:" + backend.getAddress().getPort(),
                        "--proxy.io-threads=1", "--proxy.max-concurrent=1",
                        "--proxy.connections-per-origin-per-loop=1", "--proxy.exchange-timeout=20s",
                        "--proxy-command.max-concurrent=1", "--proxy-command.timeout=15s",
                        "--spring.main.banner-mode=off");
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

        void awaitReleased() {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertEquals(0, engine.activeRequests());
                assertEquals(1, commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
            });
        }

        private void serve(HttpExchange exchange) throws IOException {
            boolean large = exchange.getRequestURI().getPath().equals("/large");
            try (exchange) {
                byte[] block = new byte[BLOCK];
                int length = large ? LARGE_BODY : RECOVERY_BODY;
                int seed = large ? 11 : 93;
                // One additional byte keeps the first response incomplete even if the OS
                // unexpectedly buffers every preceding byte before the client's reset.
                exchange.sendResponseHeaders(200, large ? length + 1L : length);
                int offset = 0;
                while (offset < length) {
                    int count = Math.min(BLOCK, length - offset);
                    fill(block, offset, count, seed, 0);
                    exchange.getResponseBody().write(block, 0, count);
                    offset += count;
                    if (large && offset == BLOCK) {
                        exchange.getResponseBody().flush();
                        awaitGate(sendRemaining);
                        largeTransferStarted.countDown();
                    }
                }
                if (large) {
                    awaitGate(finishLarge);
                    exchange.getResponseBody().write(pattern(length, seed));
                }
            } catch (IOException failure) {
                if (!large) throw failure;
                // Expected when the stopped downstream resets and the proxy closes upstream.
            } finally {
                if (large) largeHandlerFinished.countDown();
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
            sendRemaining.countDown();
            finishLarge.countDown();
            client.shutdownNow();
            application.close();
            backend.stop(0);
            backendThreads.shutdownNow();
        }
    }
}
