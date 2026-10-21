package dev.example.app;

import dev.example.proxy.transport.ProxyEngine;
import dev.example.resilience.CommandExecutor;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет остановку именно Spring/Jetty приложения с двумя активными потоками. */
@Timeout(30)
class ProxyShutdownIntegrationTest {
    private static final byte[] PARTIAL_UPLOAD = "partial-upload".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIRST_DOWNLOAD = "first".getBytes(StandardCharsets.US_ASCII);

    @Test
    void boundedGracefulShutdownAbortsActiveStreamsAndReleasesBothAdmissionsAndSockets() throws Exception {
        Set<Thread> previousThreads = proxyThreads();
        try (Upstream upstream = new Upstream()) {
            ConfigurableApplicationContext application = new SpringApplicationBuilder(ProxyApplication.class).run(
                    "--server.port=0", "--server.shutdown=graceful",
                    "--spring.lifecycle.timeout-per-shutdown-phase=500ms",
                    "--proxy.upstream=http://127.0.0.1:" + upstream.port(),
                    "--proxy.io-threads=1", "--proxy.max-concurrent=2",
                    "--proxy.connections-per-origin-per-loop=2",
                    "--proxy-command.max-concurrent=2", "--proxy-command.timeout=60s",
                    "--proxy.exchange-timeout=60s", "--proxy.response-headers-timeout=60s",
                    "--spring.main.banner-mode=off");
            AtomicReference<Thread> stoppingThread = new AtomicReference<>();
            try {
                Object server = application.getClass().getMethod("getWebServer").invoke(application);
                int port = (int) server.getClass().getMethod("getPort").invoke(server);
                ProxyEngine engine = application.getBean(ProxyEngine.class);
                CommandExecutor<?> commands = application.getBean("proxyCommands", CommandExecutor.class);
                try (Socket uploading = new Socket("127.0.0.1", port);
                     HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                             .connectTimeout(Duration.ofSeconds(3)).build()) {
                    uploading.setSoTimeout(3000);
                    uploading.getOutputStream().write(("POST /proxy/upload HTTP/1.1\r\nHost: localhost\r\n"
                            + "Content-Length: 1000000\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    uploading.getOutputStream().write(PARTIAL_UPLOAD);
                    uploading.getOutputStream().flush();
                    assertTrue(upstream.uploadStarted.await(3, TimeUnit.SECONDS), "Upload must reach the backend");

                    HttpResponse<InputStream> download = client.send(HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + port + "/proxy/download"))
                            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                    assertEquals(200, download.statusCode());
                    try (InputStream body = download.body()) {
                        assertArrayEquals(FIRST_DOWNLOAD, body.readNBytes(FIRST_DOWNLOAD.length));
                        assertEquals(2, engine.activeRequests());
                        assertEquals(0, commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
                        Set<Thread> ownedThreads = proxyThreads();
                        ownedThreads.removeAll(previousThreads);
                        assertFalse(ownedThreads.isEmpty(), "Traffic must have started this application's I/O loop");

                        // Не дожидаемся deadlines обмена. Shutdown обязан сам отменить оба потока
                        // после короткого grace period; отдельный daemon ограничивает время проверки.
                        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
                        Thread stopping = Thread.ofPlatform().daemon().name("test-application-shutdown").unstarted(() -> {
                            try { application.close(); }
                            catch (Throwable failure) { shutdownFailure.set(failure); }
                        });
                        stoppingThread.set(stopping);
                        stopping.start();
                        stopping.join(10_000);
                        assertFalse(stopping.isAlive(), "Application shutdown exceeded its bounded test budget");
                        assertNull(shutdownFailure.get());

                        // Backend никогда не отправлял final chunk. Чистый EOF означал бы ложный
                        // успешный end marker от контейнера после усечения ответа.
                        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                                assertThrows(IOException.class, body::read, "Incomplete chunked response must fail"));
                        assertUploadDidNotSucceed(uploading);

                        assertTrue(upstream.bothClosed.await(3, TimeUnit.SECONDS),
                                "Both physical upstream sockets must close before fixture cleanup");
                        upstream.assertHandlersSucceeded();
                        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                            assertEquals(0, engine.activeRequests());
                            assertEquals(2, commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
                            assertTrue(ownedThreads.stream().noneMatch(Thread::isAlive),
                                    "The application's proxy-io threads must terminate");
                        });
                    }
                }
            } finally {
                // При провале bounded shutdown не входим повторно в тот же lifecycle lock.
                // Клиентские/fixture sockets закрываются своими finally, помогая завершить teardown.
                if (stoppingThread.get() == null) application.close();
            }
        }
    }

    private static void assertUploadDidNotSucceed(Socket socket) throws IOException {
        try {
            String statusLine = line(socket.getInputStream());
            // Before headers, either an error response or transport close is legitimate.
            if (statusLine != null) {
                int status = Integer.parseInt(statusLine.split(" ", 3)[1]);
                assertTrue(status >= 500 && status <= 599, "Incomplete upload must not succeed: " + statusLine);
            }
        } catch (SocketException connectionClosed) {
            // Jetty may reset an unread request when stopping the connector.
        }
    }

    private static Set<Thread> proxyThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith("proxy-io-"))
                .collect(Collectors.toSet());
    }

    private static String line(InputStream input) throws IOException {
        StringBuilder text = new StringBuilder();
        while (text.length() < 16_384) {
            int next = input.read();
            if (next < 0) {
                if (text.isEmpty()) return null;
                throw new IOException("Truncated HTTP line");
            }
            text.append((char) next);
            if (text.length() >= 2 && text.charAt(text.length() - 2) == '\r' && next == '\n')
                return text.substring(0, text.length() - 2);
        }
        throw new IOException("Oversized fixture HTTP line");
    }

    private static final class Upstream implements AutoCloseable {
        final CountDownLatch uploadStarted = new CountDownLatch(1);
        final CountDownLatch bothClosed = new CountDownLatch(2);
        final ServerSocket listener = new ServerSocket();
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final ConcurrentLinkedQueue<Future<?>> handlers = new ConcurrentLinkedQueue<>();
        final Future<?> accepting;

        Upstream() throws IOException {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            listener.setSoTimeout(10_000);
            accepting = workers.submit(() -> {
                for (int i = 0; i < 2; i++) {
                    Socket socket = listener.accept();
                    sockets.add(socket);
                    handlers.add(workers.submit(() -> { serve(socket); return null; }));
                }
                return null;
            });
        }

        int port() { return listener.getLocalPort(); }

        void serve(Socket socket) throws IOException {
            try (socket) {
                socket.setSoTimeout(15_000);
                InputStream input = socket.getInputStream();
                String request = line(input);
                assertNotNull(request);
                for (String header; (header = line(input)) != null && !header.isEmpty(); ) { }
                if (request.startsWith("POST /upload ")) {
                    assertArrayEquals(PARTIAL_UPLOAD, input.readNBytes(PARTIAL_UPLOAD.length));
                    uploadStarted.countDown();
                } else {
                    assertTrue(request.startsWith("GET /download "), request);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n"
                            + "Content-Type: application/octet-stream\r\n\r\n5\r\nfirst\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                // No response terminator or application-side close: observe the proxy's actual FIN/RST.
                try { while (input.read() >= 0) { } }
                catch (SocketException reset) { /* A reset also proves physical closure. */ }
                bothClosed.countDown();
            } finally {
                sockets.remove(socket);
            }
        }

        void assertHandlersSucceeded() throws Exception {
            accepting.get(1, TimeUnit.SECONDS);
            for (Future<?> handler : handlers) handler.get(1, TimeUnit.SECONDS);
        }

        @Override public void close() throws Exception {
            listener.close();
            for (Socket socket : sockets) socket.close();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS), "Fixture threads must terminate");
        }
    }
}
