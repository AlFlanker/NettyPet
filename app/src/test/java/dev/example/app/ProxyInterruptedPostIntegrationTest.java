package dev.example.app;

import dev.example.app.command.ProxyCommands;
import dev.example.proxy.transport.ProxyEngine;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет отсутствие повтора POST после чтения части тела и обрыва целевого сервера. */
@Timeout(20)
class ProxyInterruptedPostIntegrationTest {
    private static final byte[] PREFIX = "partial-post-body".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIRST = "first".getBytes(StandardCharsets.US_ASCII);

    @Test
    void upstreamCloseAfterPartialPostReturns502WithoutRetryAndReleasesBothAdmissions() throws Exception {
        interruptedPost(false);
    }

    @Test
    void upstreamCloseAfterCommittedResponseAbortsWithoutRetryAndReleasesBothAdmissions() throws Exception {
        interruptedPost(true);
    }

    private static void interruptedPost(boolean commitResponse) throws Exception {
        try (Upstream backend = new Upstream(commitResponse)) {
            try (ConfigurableApplicationContext application = new SpringApplicationBuilder(ProxyApplication.class).run(
                    "--server.port=0", "--proxy.upstream=http://127.0.0.1:" + backend.port(),
                    "--proxy.io-threads=1", "--proxy.max-concurrent=1", "--proxy-command.max-concurrent=1",
                    "--proxy.exchange-timeout=5s", "--proxy-command.timeout=5s",
                    "--spring.main.banner-mode=off")) {
                Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
                int port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
                ProxyEngine engine = application.getBean(ProxyEngine.class);
                ProxyCommands commands = application.getBean(ProxyCommands.class);
                try (Socket client = new Socket()) {
                    client.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                    client.setSoTimeout(5000);
                    client.getOutputStream().write(("POST /proxy/upload HTTP/1.1\r\nHost: localhost\r\n"
                            + "Content-Length: 1048576\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(PREFIX);
                    client.getOutputStream().flush();
                    assertTrue(backend.partialBodyReceived.await(3, TimeUnit.SECONDS),
                            "The backend must consume part of the original POST before closing");
                    assertEquals(1, engine.activeRequests());
                    assertEquals(0, commands.bulkhead().getMetrics().getAvailableConcurrentCalls());

                    if (commitResponse) {
                        InputStream response = client.getInputStream();
                        assertEquals("HTTP/1.1 200 OK", line(response));
                        boolean promisedMoreBody = false;
                        for (String header; (header = line(response)) != null && !header.isEmpty(); ) {
                            if (header.equalsIgnoreCase("Content-Length: 10")) promisedMoreBody = true;
                        }
                        assertTrue(promisedMoreBody, "The client must expect ten bytes before the backend closes");
                        assertArrayEquals(FIRST, response.readNBytes(FIRST.length));
                        // Закрываем соединение с внешним сервером только после получения клиентом начала тела ответа.
                        backend.closeConnection.countDown();
                        assertEquals(0, response.readAllBytes().length,
                                "An aborted response must end before the remaining five promised bytes arrive");
                    } else {
                        backend.closeConnection.countDown();
                        String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                        assertTrue(response.startsWith("HTTP/1.1 502"), response);
                    }
                    await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                        assertEquals(0, engine.activeRequests());
                        assertEquals(1, commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
                    });
                    assertNull(backend.failure.get(), "Raw backend assertions must succeed");
                    assertEquals(1, backend.requests.get(), "A partial one-shot POST must never be retried");
                    assertEquals(1, backend.connections.get(), "No replacement upstream connection may be opened");
                }
            }
        }
    }

    private static String line(InputStream input) throws IOException {
        StringBuilder value = new StringBuilder();
        while (value.length() < 16384) {
            int next = input.read();
            if (next < 0) return value.isEmpty() ? null : value.toString();
            value.append((char) next);
            if (value.length() >= 2 && value.substring(value.length() - 2).equals("\r\n"))
                return value.substring(0, value.length() - 2);
        }
        throw new IOException("Unexpectedly long HTTP line");
    }

    private static final class Upstream implements AutoCloseable {
        final ServerSocket listener = new ServerSocket();
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final CountDownLatch partialBodyReceived = new CountDownLatch(1);
        final CountDownLatch closeConnection = new CountDownLatch(1);
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final boolean commitResponse;

        Upstream(boolean commitResponse) throws IOException {
            this.commitResponse = commitResponse;
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            workers.execute(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket socket = listener.accept();
                        connections.incrementAndGet();
                        sockets.add(socket);
                        workers.execute(() -> serve(socket));
                    } catch (SocketException closing) {
                        if (!listener.isClosed()) failure.compareAndSet(null, closing);
                        return;
                    } catch (Throwable unexpected) {
                        failure.compareAndSet(null, unexpected);
                        return;
                    }
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        private void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(5000);
                InputStream input = socket.getInputStream();
                assertEquals("POST /upload HTTP/1.1", line(input));
                requests.incrementAndGet();
                boolean declaredFullBody = false;
                for (String header; (header = line(input)) != null && !header.isEmpty(); ) {
                    if (header.equalsIgnoreCase("Content-Length: 1048576")) declaredFullBody = true;
                }
                assertTrue(declaredFullBody);
                assertArrayEquals(PREFIX, input.readNBytes(PREFIX.length));
                partialBodyReceived.countDown();
                if (commitResponse) {
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n"
                            + "Connection: close\r\n\r\nfirst")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                assertTrue(closeConnection.await(5, TimeUnit.SECONDS));
                // Закрываем физический сокет, не завершая тело ответа: прокси не должен повторить запрос.
            } catch (Throwable unexpected) {
                failure.compareAndSet(null, unexpected);
                partialBodyReceived.countDown();
            } finally {
                sockets.remove(socket);
            }
        }

        @Override public void close() throws Exception {
            closeConnection.countDown();
            listener.close();
            for (Socket socket : sockets) socket.close();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
