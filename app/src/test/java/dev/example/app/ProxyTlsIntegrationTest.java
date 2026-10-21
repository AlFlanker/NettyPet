package dev.example.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.example.proxy.transport.ProxyEngine;
import dev.example.app.command.ProxyCommands;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверяет штатную проверку доверия JDK и имени HTTPS-сервера через настоящее TLS-соединение.
 * Тест не отключает проверку сертификатов и не меняет производственную TLS-конфигурацию.
 * Сертификаты создаются заново, чтобы не зависеть от срока действия сохранённых образцов;
 * глобальные настройки доверия процесса восстанавливаются после проверки.
 */
@Timeout(30)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ProxyTlsIntegrationTest {
    private static final char[] PASSWORD = "test-only-password".toCharArray();
    private static final byte[] FIRST = "first\n".getBytes(StandardCharsets.US_ASCII);
    private static final Map<String, String> ORIGINAL_TRUST_PROPERTIES = new HashMap<>();
    @TempDir static Path certificates;
    private static SSLContext trustedServer;
    private static SSLContext untrustedServer;

    @BeforeAll
    static void createCertificatesAndInstallTestTrustStore() throws Exception {
        KeyStore trusted = createIdentity("trusted");
        KeyStore untrusted = createIdentity("untrusted");
        trustedServer = serverContext(trusted);
        untrustedServer = serverContext(untrusted);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, PASSWORD);
        trust.setCertificateEntry("trusted-localhost", trusted.getCertificate("server"));
        Path trustStore = certificates.resolve("trust.p12");
        try (var output = Files.newOutputStream(trustStore)) { trust.store(output, PASSWORD); }
        setTrustProperty("javax.net.ssl.trustStore", trustStore.toString());
        setTrustProperty("javax.net.ssl.trustStoreType", "PKCS12");
        setTrustProperty("javax.net.ssl.trustStorePassword", new String(PASSWORD));
    }

    @AfterAll
    static void restoreTrustProperties() {
        ORIGINAL_TRUST_PROPERTIES.forEach((name, value) -> {
            if (value == null) System.clearProperty(name);
            else System.setProperty(name, value);
        });
        ORIGINAL_TRUST_PROPERTIES.clear();
    }

    @Test
    void trustedTlsStreamsBeforeBodyEndsAndReusesOnlyTheCompletedConnection() throws Exception {
        try (Fixture f = new Fixture(trustedServer, "localhost", "5s")) {
            HttpResponse<InputStream> response = f.client.send(f.request("/stream"), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertArrayEquals(FIRST, body.readNBytes(FIRST.length), "TLS must not aggregate the whole response");
                assertEquals(1, f.engine.activeRequests());
                assertEquals(0, f.commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
                f.releaseStream.countDown();
                assertEquals("last\n", new String(body.readAllBytes(), StandardCharsets.US_ASCII));
            }
            f.awaitReleased();
            assertEquals("secure", f.get("/ok").body());
            f.awaitReleased();
            assertEquals(2, f.backendRequests.get());
            assertEquals(1, f.backendPeers.size(), "A complete TLS exchange should return a reusable upstream channel");
        }
    }

    @Test
    void trustedCertificateForDifferentHostnameIsRejectedBeforeHttpReachesUpstream() throws Exception {
        // Доверенный сертификат содержит только DNS:localhost, без IP-адреса в SAN.
        assertTlsRejected(trustedServer, "127.0.0.1");
    }

    @Test
    void untrustedCertificateIsRejectedBeforeHttpReachesUpstream() throws Exception {
        assertTlsRejected(untrustedServer, "localhost");
    }

    @Test
    void timeLimiterAbortsTlsStreamAndReleasesBothAdmissionsBeforeNextRequest() throws Exception {
        try (Fixture f = new Fixture(trustedServer, "localhost", "1s")) {
            HttpResponse<InputStream> response = f.client.send(f.request("/stream"), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertArrayEquals(FIRST, body.readNBytes(FIRST.length));
                var remainder = f.backendThreads.submit(body::readAllBytes);
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> remainder.get(4, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, failure.getCause(), "Cancellation must not turn truncation into a normal EOF");
            }
            f.awaitReleased();
            f.releaseStream.countDown();
            assertEquals("secure", f.get("/ok").body());
            f.awaitReleased();
            assertEquals(2, f.backendRequests.get());
            assertEquals(2, f.backendPeers.size(), "Cancelled TLS channel must not be reused");
        }
    }

    private static void assertTlsRejected(SSLContext serverContext, String host) throws Exception {
        try (Fixture f = new Fixture(serverContext, host, "5s")) {
            HttpResponse<String> response = f.get("/ok");
            assertEquals(502, response.statusCode());
            assertEquals("", response.body());
            f.awaitReleased();
            assertEquals(0, f.backendRequests.get(), "TLS verification must finish before HTTP request bytes are sent");
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertEquals(1, f.commands.circuitBreaker().getMetrics().getNumberOfFailedCalls()));
        }
    }

    private static void setTrustProperty(String name, String value) {
        ORIGINAL_TRUST_PROPERTIES.put(name, System.getProperty(name));
        System.setProperty(name, value);
    }

    private static KeyStore createIdentity(String name) throws Exception {
        Path store = certificates.resolve(name + ".p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        Path log = certificates.resolve(name + ".log");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server",
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", new String(PASSWORD), "-noprompt")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "keytool did not finish");
            assertEquals(0, process.exitValue(), () -> "keytool failed; see " + log);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        KeyStore identity = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { identity.load(input, PASSWORD); }
        return identity;
    }

    private static SSLContext serverContext(KeyStore identity) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(identity, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    private static final class Fixture implements AutoCloseable {
        final CountDownLatch releaseStream = new CountDownLatch(1);
        final AtomicInteger backendRequests = new AtomicInteger();
        final Set<InetSocketAddress> backendPeers = ConcurrentHashMap.newKeySet();
        final ExecutorService backendThreads = Executors.newVirtualThreadPerTaskExecutor();
        final HttpsServer backend;
        final ConfigurableApplicationContext application;
        final HttpClient client;
        final ProxyEngine engine;
        final ProxyCommands commands;
        final int port;

        Fixture(SSLContext serverContext, String host, String timeout) throws Exception {
            backend = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backend.setHttpsConfigurator(new HttpsConfigurator(serverContext));
            backend.setExecutor(backendThreads);
            backend.createContext("/", this::serve);
            backend.start();
            try {
                application = new SpringApplicationBuilder(ProxyApplication.class).run("--server.port=0",
                        "--proxy.upstream=https://" + host + ":" + backend.getAddress().getPort(),
                        "--proxy.io-threads=1", "--proxy.max-concurrent=1",
                        "--proxy.connections-per-origin-per-loop=1", "--proxy.connect-timeout=3s",
                        "--proxy.exchange-timeout=10s", "--proxy-command.max-concurrent=1",
                        "--proxy-command.timeout=" + timeout, "--spring.main.banner-mode=off");
            } catch (Throwable failure) {
                backend.stop(0);
                backendThreads.shutdownNow();
                throw failure;
            }
            Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
            port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
            engine = application.getBean(ProxyEngine.class);
            commands = application.getBean("proxyCommands", ProxyCommands.class);
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
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertEquals(0, engine.activeRequests());
                assertEquals(1, commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
            });
        }

        private void serve(HttpExchange exchange) throws IOException {
            try (exchange) {
                backendRequests.incrementAndGet();
                backendPeers.add(exchange.getRemoteAddress());
                if (exchange.getRequestURI().getPath().equals("/stream")) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(FIRST);
                    exchange.getResponseBody().flush();
                    try {
                        if (!releaseStream.await(10, TimeUnit.SECONDS)) throw new IOException("Test gate timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                    exchange.getResponseBody().write("last\n".getBytes(StandardCharsets.US_ASCII));
                } else {
                    byte[] bytes = "secure".getBytes(StandardCharsets.US_ASCII);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            }
        }

        @Override public void close() {
            releaseStream.countDown();
            client.shutdownNow();
            application.close();
            backend.stop(0);
            backendThreads.shutdownNow();
        }
    }
}
