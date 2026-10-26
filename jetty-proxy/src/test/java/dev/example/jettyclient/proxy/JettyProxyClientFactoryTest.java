package dev.example.jettyclient.proxy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class JettyProxyClientFactoryTest {
    @Test
    void ordinaryClientIsRejectedInsteadOfSilentlyNormalizingExtensionMethods() throws Exception {
        HttpClient ordinary = new HttpClient();
        try {
            assertEquals("HEAD", ordinary.newRequest("http://backend.test/").method("head").getMethod());
            IllegalArgumentException engine = assertThrows(IllegalArgumentException.class,
                    () -> new JettyProxyEngine(ordinary, JettyProxyProperties.defaults()));
            IllegalArgumentException fetch = assertThrows(IllegalArgumentException.class,
                    () -> new ProxyFetchClient(ordinary, JettyProxyProperties.defaults()));
            assertTrue(engine.getMessage().contains("JettyProxyClientFactory"));
            assertTrue(fetch.getMessage().contains("JettyProxyClientFactory"));
            assertNull(ordinary.getExecutor(), "Rejection does not start or change the supplied client");
        } finally { ordinary.stop(); }
    }

    @Test
    void customizerRunsOnceBeforeStartAndPreservesExecutorNameAndConnectionLimits() throws Exception {
        QueuedThreadPool executor = new QueuedThreadPool(24, 8);
        executor.setName("host-proxy-executor");
        executor.setReservedThreads(0);
        AtomicInteger calls = new AtomicInteger();
        HttpClient client = null;
        try {
            client = JettyProxyClientFactory.createStarted(JettyProxyProperties.defaults(), ProxyTlsProfile.systemTrust(), candidate -> {
                assertTrue(candidate.isStopped());
                calls.incrementAndGet();
                candidate.setName("host-proxy-client");
                candidate.setExecutor(executor);
                candidate.setMaxConnectionsPerDestination(3);
                candidate.setMaxRequestsQueuedPerDestination(7);
            });
            assertEquals(1, calls.get());
            assertSame(executor, client.getExecutor());
            assertEquals("host-proxy-client", client.getName());
            assertEquals(3, client.getMaxConnectionsPerDestination());
            assertEquals(7, client.getMaxRequestsQueuedPerDestination());
            assertSame(client, JettyProxyClientFactory.requireCompatible(client));
            assertEquals("mIxEd", client.newRequest("http://backend.test/").method("mIxEd").getMethod());
        } finally {
            if (client != null) client.stop();
            executor.stop();
        }
    }

    @Test
    void callbackCannotEnableSharedCookiesCredentialsRedirectOrUnsafeTls() throws Exception {
        URI backend = URI.create("http://backend.test/");
        var unsafeCookies = new HttpCookieStore.Default();
        var unsafeTls = new SslContextFactory.Client(true);
        unsafeTls.setEndpointIdentificationAlgorithm(null);
        var credentials = new BasicAuthentication.BasicResult(backend, "fixture-user", "fixture-password");
        JettyProxyProperties properties = JettyProxyProperties.defaults();
        HttpClient client = JettyProxyClientFactory.createStarted(properties, ProxyTlsProfile.systemTrust(), candidate -> {
            unsafeCookies.add(backend, HttpCookie.from("shared", "must-not-leak"));
            candidate.setHttpCookieStore(unsafeCookies);
            candidate.getAuthenticationStore().addAuthenticationResult(credentials);
            candidate.setFollowRedirects(true);
            candidate.setSslContextFactory(unsafeTls);
            candidate.setUserAgentField(new HttpField("User-Agent", "injected-default"));
            candidate.setDefaultRequestContentType("application/injected");
            candidate.setResponseBufferSize(1024 * 1024);
            candidate.setMaxResponseHeadersSize(-1);
        });
        try {
            assertInstanceOf(HttpCookieStore.Empty.class, client.getHttpCookieStore());
            assertTrue(client.getHttpCookieStore().all().isEmpty());
            assertNull(client.getAuthenticationStore().findAuthenticationResult(backend));
            client.getAuthenticationStore().addAuthenticationResult(credentials);
            assertNull(client.getAuthenticationStore().findAuthenticationResult(backend));
            assertFalse(client.isFollowRedirects());
            assertNotSame(unsafeTls, client.getSslContextFactory());
            assertFalse(client.getSslContextFactory().isTrustAll());
            assertEquals("HTTPS", client.getSslContextFactory().getEndpointIdentificationAlgorithm());
            assertNull(client.getUserAgentField());
            assertNull(client.getDefaultRequestContentType());
            assertTrue(client.getContentDecoderFactories().isEmpty());
            assertEquals(properties.chunkBytes(), client.getResponseBufferSize());
            assertEquals(properties.maxResponseHeadersBytes(), client.getMaxResponseHeadersSize());
        } finally { client.stop(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callbackCannotStartTheClientEvenIfItStopsItAgain(boolean stopAgain) {
        AtomicReference<HttpClient> supplied = new AtomicReference<>();
        assertThrows(IllegalArgumentException.class, () -> JettyProxyClientFactory.createStarted(
                JettyProxyProperties.defaults(), ProxyTlsProfile.systemTrust(), candidate -> {
                    supplied.set(candidate);
                    try {
                        candidate.start();
                        if (stopAgain) candidate.stop();
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                }));
        assertNotNull(supplied.get());
        assertTrue(supplied.get().isStopped(), "A rejected callback-started client must not leave resources running");
    }

    @Test
    void callbackFailureStopsThePartiallyConfiguredClientAndPreservesTheCause() {
        AtomicReference<HttpClient> supplied = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("configuration failed");
        var actual = assertThrows(IllegalStateException.class, () -> JettyProxyClientFactory.createStarted(
                JettyProxyProperties.defaults(), ProxyTlsProfile.systemTrust(), candidate -> {
                    supplied.set(candidate);
                    throw failure;
                }));
        assertSame(failure, actual);
        assertTrue(supplied.get().isStopped());
    }

    @Test
    void supportedClientSendsLowercaseHeadAsAnExtensionMethodWithItsBody() throws Exception {
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            backend.setSoTimeout(3000);
            var received = worker.submit(() -> {
                try (var socket = backend.accept()) {
                    socket.setSoTimeout(3000);
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line = input.readLine();
                    String method = line.substring(0, line.indexOf(' '));
                    while (!input.readLine().isEmpty()) { }
                    byte[] body = method.getBytes(StandardCharsets.US_ASCII);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                    return method;
                }
            });
            HttpClient client = JettyProxyClientFactory.createStarted(JettyProxyProperties.defaults());
            try {
                var response = client.newRequest("http://127.0.0.1:" + backend.getLocalPort() + "/")
                        .method("head").timeout(3, TimeUnit.SECONDS).send();
                assertEquals("head", received.get(3, TimeUnit.SECONDS));
                assertEquals(200, response.getStatus());
                assertEquals("head", response.getContentAsString(), "Lowercase head must not acquire HEAD body semantics");
            } finally { client.stop(); }
        }
    }
}
