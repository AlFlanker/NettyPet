package dev.example.jettyclient.proxy;

import java.util.Objects;
import org.eclipse.jetty.client.ContinueProtocolHandler;
import org.eclipse.jetty.client.EarlyHintsProtocolHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ProcessingProtocolHandler;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpCookieStore;

/** Создаёт общий HTTP/1.1-клиент с прозрачной для прокси обработкой ответа upstream. */
public final class JettyProxyClientFactory {
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
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(profile, "profile");
        var client = new ProxyHttpClient(new HttpClientTransportOverHTTP(properties.selectors()));
        client.setName("jetty-proxy-upstream");
        client.setSslContextFactory(profile.createContext());
        client.setFollowRedirects(false);
        client.setHttpCookieStore(new HttpCookieStore.Empty());
        client.setUserAgentField(null);
        client.setDefaultRequestContentType(null);
        client.setMaxConnectionsPerDestination(properties.maxConnectionsPerDestination());
        client.setMaxRequestsQueuedPerDestination(properties.maxQueuedRequestsPerDestination());
        client.setResponseBufferSize(properties.chunkBytes());
        client.setMaxResponseHeadersSize(properties.maxResponseHeadersBytes());
        client.setConnectTimeout(properties.connectTimeout().toMillis());
        client.setIdleTimeout(properties.idleTimeout().toMillis());
        client.setDestinationIdleTimeout(properties.destinationIdleTimeout().toMillis());
        try {
            client.start();
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
}
