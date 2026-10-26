package dev.example.jettyclient.proxy;

import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.client.ContinueProtocolHandler;
import org.eclipse.jetty.client.EarlyHintsProtocolHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ProcessingProtocolHandler;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

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

    /** Один transport для Servlet и подключаемых приложением Filter-адаптеров. */
    @Bean(destroyMethod = "close")
    JettyProxyEngine jettyProxyEngine(HttpClient jettyHttpClient, JettyProxyProperties properties,
                                     ObjectProvider<ProxyHeaderCustomizer> customizers) {
        return new JettyProxyEngine(jettyHttpClient, properties, customizers.orderedStream().toList());
    }

    /** Необязательный Servlet-адаптер использует тот же engine, не владея им. */
    @Bean(destroyMethod = "")
    JettyProxyServlet jettyProxyServlet(JettyProxyEngine engine, JettyProxyProperties properties) {
        return new JettyProxyServlet(engine, properties.upstream());
    }

    @Bean
    ServletRegistrationBean<JettyProxyServlet> jettyProxyServletRegistration(JettyProxyServlet servlet) {
        var registration = new ServletRegistrationBean<>(servlet, "/proxy", "/proxy/*");
        registration.setName("jettyProxyServlet");
        // Prevent Boot's automatic Servlet registration: the example now enters via Filter.
        registration.setEnabled(false);
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    private static ProxyRequestPlanner fixedUpstreamPlanner(JettyProxyProperties properties) {
        return request -> {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (!(path.equals("/proxy") || path.startsWith("/proxy/"))) return null;
            return ProxyRequestPlan.builder(ProxyHeaders.target(properties.upstream(), request)).build();
        };
    }

    @Bean
    FilterRegistrationBean<JettyProxyFilter> jettyProxyFilterRegistration(
            JettyProxyEngine engine, JettyProxyProperties properties, ObjectProvider<ProxyRequestPlanner> planners) {
        ProxyRequestPlanner planner = planners.getIfAvailable(() -> fixedUpstreamPlanner(properties));
        var registration = new FilterRegistrationBean<>(new JettyProxyFilter(engine, planner));
        registration.setName("jettyProxyFilter");
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setAsyncSupported(true);
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        return registration;
    }
}
