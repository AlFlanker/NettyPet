package dev.example.jettyclient.proxy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Real TLS and mTLS handshakes; generated fixture credentials are never production secrets. */
@Timeout(15)
class JettyProxyTlsIntegrationTest {
    private static final char[] PASSWORD = "tls-fixture-password".toCharArray();
    private static final AtomicReference<Throwable> FAILURE = new AtomicReference<>();
    @TempDir static Path directory;
    private static HttpsServer server;
    private static ExecutorService workers;

    @BeforeAll
    @Timeout(60)
    static void start() throws Exception {
        generate("server", "server.p12", "CN=localhost", true);
        generate("client-one", "clients.p12", "CN=client-one", false);
        generate("client-two", "clients.p12", "CN=client-two", false);
        export("server", "server.p12");
        export("client-one", "clients.p12");
        export("client-two", "clients.p12");
        trust("server", "client-trust.p12");
        trust("client-one", "server-trust.p12");
        trust("client-two", "server-trust.p12");
        // Identity stores may contain public certificate entries. They are not
        // authority to trust a server unless a trust store is explicitly chosen.
        trust("server", "clients.p12");

        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(load("server.p12"), PASSWORD);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(load("server-trust.p12"));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override public void configure(HttpsParameters parameters) {
                SSLParameters configured = getSSLContext().getDefaultSSLParameters();
                configured.setNeedClientAuth(true);
                parameters.setSSLParameters(configured);
            }
        });
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/identity", exchange -> {
            try {
                byte[] body = ((HttpsExchange)exchange).getSSLSession().getPeerPrincipal().getName().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Throwable failure) {
                FAILURE.compareAndSet(null, failure);
            } finally { exchange.close(); }
        });
        server.start();
    }

    @AfterEach
    void fixtureRemainsHealthy() { assertNull(FAILURE.get(), "The independent mTLS fixture must complete correctly"); }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    @Test
    void theDefaultClientRejectsAnUntrustedServerCertificate() throws Exception {
        HttpClient client = JettyProxyClientFactory.createStarted(JettyProxyProperties.defaults());
        try {
            assertFalse(client.getSslContextFactory().isTrustAll());
            assertEquals("HTTPS", client.getSslContextFactory().getEndpointIdentificationAlgorithm());
            Exception failure = assertThrows(Exception.class, () -> client.newRequest(uri("localhost"))
                    .timeout(3, TimeUnit.SECONDS).send());
            assertTrue(hasCause(failure, SSLHandshakeException.class), () -> failure.getClass().getName());
        } finally { client.stop(); }
    }

    @Test
    void profilesSelectDistinctClientKeysAndNeverShareTheirConnectionIdentity() throws Exception {
        char[] supplied = PASSWORD.clone();
        ProxyTlsProfile one = ProxyTlsProfile.builder("partner-one:v1")
                .trustStore(directory.resolve("client-trust.p12"), supplied)
                .keyStore(directory.resolve("clients.p12"), supplied).keyAlias("client-one").build();
        // Mutating caller arrays and builder inputs cannot change the built credentials.
        Arrays.fill(supplied, 'x');
        HttpClient clientOne = JettyProxyClientFactory.createStarted(JettyProxyProperties.defaults(), one);
        HttpClient clientTwo = null;
        try {
            clientTwo = JettyProxyClientFactory.createStarted(JettyProxyProperties.defaults(), profile("client-two"));
            assertEquals("CN=client-one", identity(clientOne));
            assertEquals("CN=client-two", identity(clientTwo));
            assertEquals("CN=client-one", identity(clientOne));
            assertNotSame(clientOne.getSslContextFactory(), clientTwo.getSslContextFactory());
        } finally {
            if (clientTwo != null) clientTwo.stop();
            clientOne.stop();
        }
    }

    @Test
    void trustingTheIssuerDoesNotDisableHostnameVerification() throws Exception {
        HttpClient client = JettyProxyClientFactory.createStarted(JettyProxyProperties.defaults(), profile("client-one"));
        try {
            Exception failure = assertThrows(Exception.class, () -> client.newRequest(uri("127.0.0.1"))
                    .timeout(3, TimeUnit.SECONDS).send());
            assertTrue(hasCause(failure, SSLHandshakeException.class), () -> failure.getClass().getName());
            assertEquals("CN=client-one", identity(client), "The same profile still works with the certificate hostname");
        } finally { client.stop(); }
    }

    @Test
    void aClientIdentityDoesNotImplicitlyBecomeAServerTrustStore() throws Exception {
        ProxyTlsProfile identityOnly = ProxyTlsProfile.builder("identity-with-system-trust:v1")
                .keyStore(directory.resolve("clients.p12"), PASSWORD).keyAlias("client-one").build();
        HttpClient client = JettyProxyClientFactory.createStarted(JettyProxyProperties.defaults(), identityOnly);
        try {
            assertNotNull(client.getSslContextFactory().getKeyStore());
            assertNull(client.getSslContextFactory().getTrustStore(), "The identity store is not a trust store");
            Exception failure = assertThrows(Exception.class, () -> client.newRequest(uri("localhost"))
                    .timeout(3, TimeUnit.SECONDS).send());
            assertTrue(hasCause(failure, SSLHandshakeException.class), () -> failure.getClass().getName());
        } finally { client.stop(); }
    }

    private static ProxyTlsProfile profile(String alias) {
        return ProxyTlsProfile.builder(alias + ":v1")
                .trustStore(directory.resolve("client-trust.p12"), PASSWORD)
                .keyStore(directory.resolve("clients.p12"), PASSWORD)
                .keyAlias(alias).build();
    }

    private static String identity(HttpClient client) throws Exception {
        var response = client.newRequest(uri("localhost")).timeout(3, TimeUnit.SECONDS).send();
        assertEquals(200, response.getStatus());
        return response.getContentAsString();
    }

    private static URI uri(String host) { return URI.create("https://" + host + ":" + server.getAddress().getPort() + "/identity"); }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (type.isInstance(current)) return true;
        return false;
    }

    private static KeyStore load(String filename) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(directory.resolve(filename))) { store.load(input, PASSWORD); }
        return store;
    }

    private static void generate(String alias, String store, String name, boolean hostname) throws Exception {
        var arguments = new ArrayList<>(List.of("-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                "-validity", "2", "-dname", name, "-keystore", directory.resolve(store).toString(), "-storetype", "PKCS12",
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD), "-noprompt"));
        if (hostname) arguments.addAll(List.of("-ext", "SAN=DNS:localhost"));
        keytool(arguments);
    }

    private static void export(String alias, String store) throws Exception {
        keytool(List.of("-exportcert", "-rfc", "-alias", alias, "-keystore", directory.resolve(store).toString(),
                "-storepass", new String(PASSWORD), "-file", directory.resolve(alias + ".crt").toString()));
    }

    private static void trust(String alias, String store) throws Exception {
        keytool(List.of("-importcert", "-noprompt", "-alias", alias, "-file", directory.resolve(alias + ".crt").toString(),
                "-keystore", directory.resolve(store).toString(), "-storetype", "PKCS12", "-storepass", new String(PASSWORD)));
    }

    private static void keytool(List<String> arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(arguments);
        Path output = Files.createTempFile(directory, "keytool-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Fixture keytool did not finish");
        }
        assertEquals(0, process.exitValue(), "Fixture keytool must create valid test stores");
    }
}
