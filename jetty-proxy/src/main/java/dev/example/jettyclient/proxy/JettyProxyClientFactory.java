package dev.example.jettyclient.proxy;

import java.util.Objects;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.eclipse.jetty.client.Authentication;
import org.eclipse.jetty.client.AuthenticationStore;
import org.eclipse.jetty.client.ContinueProtocolHandler;
import org.eclipse.jetty.client.EarlyHintsProtocolHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ProcessingProtocolHandler;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.util.component.LifeCycle;

/** Создаёт общий HTTP/1.1-клиент с прозрачной для прокси обработкой ответа upstream. */
public final class JettyProxyClientFactory {
    private static final Consumer<HttpClient> NO_CUSTOMIZER = ignored -> { };
    private JettyProxyClientFactory() { }

    /**
     * Возвращает запущенный client. Вызывающая сторона останавливает его после
     * закрытия использующих его engines; client не создаётся на каждый запрос.
     * Redirect, shared cookie store, декодирование тела и автоматическая
     * аутентификация отключены. HTTPS проверяет доверие и имя узла.
     */
    public static HttpClient createStarted(JettyProxyProperties properties) throws Exception {
        return createStarted(properties, ProxyTlsProfile.systemTrust());
    }

    /**
     * Создаёт client для одной TLS identity. Профиль применяется до start();
     * разные identities используют разные общие clients, а не меняют SSL context
     * клиента между запросами. Повторное использование и ротация управляются приложением.
     */
    public static HttpClient createStarted(JettyProxyProperties properties, ProxyTlsProfile profile) throws Exception {
        return createStarted(properties, profile, NO_CUSTOMIZER);
    }

    /**
     * Настраивает executor, имя и сетевые параметры до start. Callback вызывается
     * один раз и не должен запускать client. TLS identity задаётся profile, а
     * proxy controls (cookies/auth/redirect/decoding/framing defaults) устанавливаются
     * после callback. Пользовательские connection/queue limits сохраняются;
     * размеры response buffer и HTTP metadata остаются заданными properties.
     */
    public static HttpClient createStarted(JettyProxyProperties properties, ProxyTlsProfile profile,
            Consumer<HttpClient> beforeStart) throws Exception {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(beforeStart, "beforeStart");
        var client = new ProxyHttpClient(new HttpClientTransportOverHTTP(properties.selectors()));
        client.setName("jetty-proxy-upstream");
        client.setSslContextFactory(profile.createContext());
        client.setMaxConnectionsPerDestination(properties.maxConnectionsPerDestination());
        client.setMaxRequestsQueuedPerDestination(properties.maxQueuedRequestsPerDestination());
        client.setResponseBufferSize(properties.chunkBytes());
        client.setMaxResponseHeadersSize(properties.maxResponseHeadersBytes());
        client.setConnectTimeout(properties.connectTimeout().toMillis());
        client.setIdleTimeout(properties.idleTimeout().toMillis());
        client.setDestinationIdleTimeout(properties.destinationIdleTimeout().toMillis());
        try {
            AtomicBoolean startedByCustomizer = new AtomicBoolean();
            var guard = new LifeCycle.Listener() {
                @Override public void lifeCycleStarting(LifeCycle event) { startedByCustomizer.set(true); }
            };
            client.addEventListener(guard);
            try { beforeStart.accept(client); }
            finally { client.removeEventListener(guard); }
            if (startedByCustomizer.get() || !client.isStopped())
                throw new IllegalArgumentException("beforeStart must not start the proxy HttpClient");
            // A callback cannot replace the typed identity or weaken TLS checks.
            var tls = profile.createContext();
            client.setSslContextFactory(tls);
            applyProxyControls(client, properties);
            client.start();
            if (client.getSslContextFactory() != tls || tls.isTrustAll()
                    || !"HTTPS".equals(tls.getEndpointIdentificationAlgorithm()))
                throw new IllegalStateException("Proxy TLS controls were changed during client startup");
            // HttpClient locks cookie/auth and other lifecycle setters at start.
            // Validate their final state rather than mutating a running client.
            if (!(client.getHttpCookieStore() instanceof HttpCookieStore.Empty)
                    || !(client.getAuthenticationStore() instanceof EmptyAuthenticationStore)
                    || client.isFollowRedirects() || client.getUserAgentField() != null
                    || client.getDefaultRequestContentType() != null
                    || client.getResponseBufferSize() != properties.chunkBytes()
                    || client.getMaxResponseHeadersSize() != properties.maxResponseHeadersBytes())
                throw new IllegalStateException("Proxy controls were changed during client startup");
            // start() заново устанавливает стандартные обработчики и декодеры.
            client.getContentDecoderFactories().clear();
            client.getProtocolHandlers().clear();
            client.getProtocolHandlers().put(new ContinueProtocolHandler());
            client.getProtocolHandlers().put(new ProcessingProtocolHandler());
            client.getProtocolHandlers().put(new EarlyHintsProtocolHandler());
            client.getProtocolHandlers().put(new InformationalProtocolHandler());
            return client;
        } catch (Exception | Error failure) {
            try { client.stop(); }
            catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    /**
     * Rejects ordinary Jetty clients, whose method setter normalizes case. Call
     * this before allocating exchange workers. Customize a compatible client via
     * createStarted(properties, profile, beforeStart), not a stock HttpClient.
     */
    public static HttpClient requireCompatible(HttpClient client) {
        Objects.requireNonNull(client, "client");
        if (!(client instanceof ProxyHttpClient))
            throw new IllegalArgumentException("Use JettyProxyClientFactory.createStarted: "
                    + "a stock HttpClient does not preserve HTTP method case");
        return client;
    }

    private static void applyProxyControls(HttpClient client, JettyProxyProperties properties) {
        client.setFollowRedirects(false);
        client.setHttpCookieStore(new HttpCookieStore.Empty());
        client.setAuthenticationStore(new EmptyAuthenticationStore());
        client.setUserAgentField(null);
        client.setDefaultRequestContentType(null);
        client.setResponseBufferSize(properties.chunkBytes());
        client.setMaxResponseHeadersSize(properties.maxResponseHeadersBytes());
    }

    /** Neither challenge processing nor cached preemptive credentials belong to a shared proxy client. */
    private static final class EmptyAuthenticationStore implements AuthenticationStore {
        @Override public void addAuthentication(Authentication authentication) { }
        @Override public void removeAuthentication(Authentication authentication) { }
        @Override public void clearAuthentications() { }
        @Override public Authentication findAuthentication(String type, URI uri, String realm) { return null; }
        @Override public void addAuthenticationResult(Authentication.Result result) { }
        @Override public void removeAuthenticationResult(Authentication.Result result) { }
        @Override public void clearAuthenticationResults() { }
        @Override public Authentication.Result findAuthenticationResult(URI uri) { return null; }
        @Override public boolean hasAuthenticationResults() { return false; }
    }
}
