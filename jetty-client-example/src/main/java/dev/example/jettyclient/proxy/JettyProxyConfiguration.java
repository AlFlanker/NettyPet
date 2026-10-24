package dev.example.jettyclient.proxy;

import org.eclipse.jetty.client.ContinueProtocolHandler;
import org.eclipse.jetty.client.EarlyHintsProtocolHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ProcessingProtocolHandler;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Собирает отдельный HTTP/1.1-клиент и Servlet; не использует модули основного прокси. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JettyProxyProperties.class)
public class JettyProxyConfiguration {
    @Bean(destroyMethod = "stop")
    HttpClient jettyHttpClient(JettyProxyProperties properties) throws Exception {
        var client = new ProxyHttpClient(new HttpClientTransportOverHTTP(properties.selectors()));
        client.setName("jetty-client-example-upstream");
        var tls = new SslContextFactory.Client(false);
        tls.setEndpointIdentificationAlgorithm("HTTPS");
        client.setSslContextFactory(tls);
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
        try {
            client.start();
            // start() устанавливает стандартные обработчики и декодеры заново.
            // Прокси передаёт ответ upstream, не выполняя redirect/auth и не распаковывая тело.
            client.getContentDecoderFactories().clear();
            client.getProtocolHandlers().clear();
            // Эти обработчики сбрасывают промежуточный ответ перед получением финального.
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

    /** destroy() вызывает Servlet-контейнер; Spring не должен вызывать его повторно. */
    @Bean(destroyMethod = "")
    JettyProxyServlet jettyProxyServlet(HttpClient jettyHttpClient, JettyProxyProperties properties,
                                     ObjectProvider<ProxyHeaderCustomizer> customizers) {
        return new JettyProxyServlet(jettyHttpClient, properties, customizers.orderedStream().toList());
    }

    @Bean
    ServletRegistrationBean<JettyProxyServlet> jettyProxyServletRegistration(JettyProxyServlet servlet) {
        var registration = new ServletRegistrationBean<>(servlet, "/proxy", "/proxy/*");
        registration.setName("jettyProxyServlet");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }
}
