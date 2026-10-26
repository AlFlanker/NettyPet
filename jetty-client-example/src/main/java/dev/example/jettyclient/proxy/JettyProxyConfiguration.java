package dev.example.jettyclient.proxy;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

/** Standalone demo only: URLs and Servlet registrations are not part of the library configuration. */
@Configuration(proxyBeanMethods = false)
@Import(JettyProxyClientConfiguration.class)
public class JettyProxyConfiguration {
    /** Optional demo Servlet shares the library engine without owning its lifecycle. */
    @Bean(destroyMethod = "")
    JettyProxyServlet jettyProxyServlet(
            @Qualifier(JettyProxyClientConfiguration.ENGINE_BEAN_NAME) JettyProxyEngine engine,
            @Qualifier(JettyProxyClientConfiguration.PROPERTIES_BEAN_NAME) JettyProxyProperties properties) {
        return new JettyProxyServlet(engine, properties.upstream());
    }

    @Bean
    ServletRegistrationBean<JettyProxyServlet> jettyProxyServletRegistration(JettyProxyServlet servlet) {
        var registration = new ServletRegistrationBean<>(servlet, "/proxy", "/proxy/*");
        registration.setName("jettyProxyServlet");
        // Suppress Boot's automatic Servlet registration: the demo enters through its Filter.
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
            @Qualifier(JettyProxyClientConfiguration.ENGINE_BEAN_NAME) JettyProxyEngine engine,
            @Qualifier(JettyProxyClientConfiguration.PROPERTIES_BEAN_NAME) JettyProxyProperties properties,
            ObjectProvider<ProxyRequestPlanner> planners) {
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
