package dev.example.jettyclientspring;

import com.sun.net.httpserver.HttpServer;
import dev.example.jettyclient.proxy.JettyProxyClientConfiguration;
import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.JettyProxyProperties;
import dev.example.jettyclient.proxy.ProxyFetchClient;
import dev.example.jettyclient.proxy.ProxyFetchRequest;
import dev.example.jettyclient.proxy.ProxyFetchOptions;
import dev.example.jettyclient.proxy.ProxyTlsProfile;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Importing the adapter is explicit; the library never installs host routes or adopts an unrelated client. */
@Timeout(15)
class JettyProxyClientConfigurationTest {
    @Test
    void componentScanningTheLibraryDoesNotActivateTransportOrRegisterServlets() {
        try (var context = new AnnotationConfigApplicationContext()) {
            // The test fixtures are deliberately outside this package, just as the host application is.
            context.scan("dev.example.jettyclient.proxy");
            context.refresh();

            assertEquals(0, context.getBeanNamesForType(HttpClient.class).length);
            assertEquals(0, context.getBeanNamesForType(JettyProxyEngine.class).length);
            assertEquals(0, context.getBeanNamesForType(ProxyFetchClient.class).length);
            assertEquals(0, context.getBeanNamesForType(Filter.class).length);
            assertEquals(0, context.getBeanNamesForType(Servlet.class).length);
        }
    }

    @Test
    void explicitImportBindsDefaultsWithoutAnApplicationConfigurationResource() {
        HttpClient owned;
        try (var context = new AnnotationConfigApplicationContext(ImportedHost.class)) {
            assertEquals(JettyProxyProperties.defaults(), context.getBean(
                    JettyProxyClientConfiguration.PROPERTIES_BEAN_NAME, JettyProxyProperties.class));
            owned = context.getBean(JettyProxyClientConfiguration.HTTP_CLIENT_BEAN_NAME, HttpClient.class);
            assertTrue(owned.isStarted());
            assertEquals(ProxyFetchOptions.defaults(), context.getBean(
                    JettyProxyClientConfiguration.FETCH_CLIENT_BEAN_NAME, ProxyFetchClient.class).options());
            assertFalse(owned.getSslContextFactory().isTrustAll());
            assertEquals("HTTPS", owned.getSslContextFactory().getEndpointIdentificationAlgorithm());
            assertEquals(0, context.getBeanNamesForType(Filter.class).length);
            assertEquals(0, context.getBeanNamesForType(Servlet.class).length);
            assertFalse(context.containsBean("jettyProxyFilterRegistration"));
            assertFalse(context.containsBean("jettyProxyServletRegistration"));
        }
        assertTrue(owned.isStopped(), "The explicitly created transport client belongs to its importing context");
    }

    @Test
    void onlyTheExplicitlyNamedTlsProfileConfiguresTheOwnedClient() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("unrelatedHostTlsProfile", ProxyTlsProfile.class,
                    () -> ProxyTlsProfile.builder("other-client").protocols("TLSv1.2").build());
            context.registerBean(JettyProxyClientConfiguration.TLS_PROFILE_BEAN_NAME, ProxyTlsProfile.class,
                    () -> ProxyTlsProfile.builder("proxy-client").protocols("TLSv1.3").build());
            context.register(ImportedHost.class);
            context.refresh();

            HttpClient owned = context.getBean(JettyProxyClientConfiguration.HTTP_CLIENT_BEAN_NAME, HttpClient.class);
            assertArrayEquals(new String[]{"TLSv1.3"}, owned.getSslContextFactory().getIncludeProtocols());
            assertFalse(owned.getSslContextFactory().isTrustAll());
            assertEquals("HTTPS", owned.getSslContextFactory().getEndpointIdentificationAlgorithm());
        }
    }

    @Test
    void qualifiedOwnedClientCoexistsWithTheHostsUnrelatedHttpClientAndServesBoundedFetch() throws Exception {
        HttpClient unrelatedHostClient = mock(HttpClient.class);
        HttpClient owned;
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/fragment", exchange -> {
            try (exchange) {
                byte[] body = "library-fragment".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        backend.start();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("hostSettings", Map.of(
                    "jetty.proxy.chunk-bytes", "8192",
                    "jetty.proxy.destination-idle-timeout", "250ms",
                    "jetty.proxy.max-concurrent", "3",
                    "jetty.proxy.fetch.max-concurrent", "5",
                    "jetty.proxy.fetch.decoder-threads", "1",
                    "jetty.proxy.fetch.max-queued", "4")));
            context.registerBean("hostApplicationHttpClient", HttpClient.class, () -> unrelatedHostClient,
                    definition -> definition.setDestroyMethodName(""));
            context.register(ImportedHost.class);
            context.refresh();

            assertEquals(2, context.getBeanNamesForType(HttpClient.class).length);
            owned = context.getBean(JettyProxyClientConfiguration.HTTP_CLIENT_BEAN_NAME, HttpClient.class);
            assertNotSame(unrelatedHostClient, owned);
            JettyProxyProperties properties = context.getBean(
                    JettyProxyClientConfiguration.PROPERTIES_BEAN_NAME, JettyProxyProperties.class);
            assertEquals(8192, properties.chunkBytes());
            assertEquals(Duration.ofMillis(250), properties.destinationIdleTimeout());
            assertEquals(250, owned.getDestinationIdleTimeout());

            ProxyFetchClient fetch = context.getBean(JettyProxyClientConfiguration.FETCH_CLIENT_BEAN_NAME,
                    ProxyFetchClient.class);
            assertEquals(new ProxyFetchOptions(3, 1, 3), fetch.options(),
                    "Fetch keeps a separate budget capped by the configured transport concurrency");
            var result = fetch.fetchBlocking(ProxyFetchRequest.builder(
                    URI.create("http://127.0.0.1:" + backend.getAddress().getPort() + "/fragment"))
                    .maxWireBytes(1024).maxDecodedBytes(1024).maxTextCharacters(1024)
                    .timeout(Duration.ofSeconds(3)).build());
            assertEquals(200, result.status());
            assertEquals("library-fragment", result.bodyAsString());

            fetch.close();
            context.getBean(JettyProxyClientConfiguration.ENGINE_BEAN_NAME, JettyProxyEngine.class).close();
            assertTrue(owned.isStarted(), "Closing either user must not stop their shared client");
            verifyNoInteractions(unrelatedHostClient);
        } finally {
            backend.stop(0);
        }
        assertTrue(owned.isStopped());
        verifyNoInteractions(unrelatedHostClient);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JettyProxyClientConfiguration.class)
    static class ImportedHost { }
}
