package dev.example.integrationhost;

import dev.example.jettyclient.proxy.JettyProxyClientConfiguration;
import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.ProxyFetchClient;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

/** Host-owned routes and handler wiring. The imported library configuration registers no routes. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HostProxyProperties.class)
@Import(JettyProxyClientConfiguration.class)
public class HostConfiguration {
    @Bean
    ProxyResourceLoader proxyResourceLoader(@Qualifier("jettyProxyFetchClient") ProxyFetchClient fetch,
            HostProxyProperties properties) {
        return new JettyResourceLoader(fetch, properties);
    }

    @Bean
    FilterRegistrationBean<HttpProxyFilter> applicationProxyFilter(HostProxyProperties properties,
            @Qualifier("jettyProxyEngine") JettyProxyEngine engine,
            ObjectProvider<ProxyRequestHandler> handlers) {
        ProxyRequestHandler handler = handlers.getIfAvailable(() -> new JettyProxyHandler(engine));
        var filter = new HttpProxyFilter(new FixedBackendRouteResolver(properties), properties::maintenance,
                ApplicationRoutePolicy::forbidden, handler);
        var registration = new FilterRegistrationBean<>(filter);
        registration.setName("applicationHttpProxyFilter");
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setAsyncSupported(true);
        // Proxy routes run before generic form parsing. Authentication, when installed,
        // belongs before this application boundary and is not configured by the library.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
