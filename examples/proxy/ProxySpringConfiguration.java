package com.otr.portal.proxy;

import com.otr.portal.core.internal.PathHelper;
import com.otr.portal.core.internal.ProxiedRequestMatcher;
import com.otr.portal.hosts.backends.BackendRegistry;
import com.otr.portal.hosts.filter.BackendForRequestFilter;
import com.otr.portal.hosts.filter.ProxyAwareFormContentFilter;
import com.otr.portal.metadata.reloadable.ConfigurationService;
import com.otr.portal.proxy.cookies.SsoCookieProperties;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.filter.OrderedFormContentFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.function.Supplier;

import static com.otr.portal.hosts.filter.BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME;

/**
 * @author pobedenniy.alexey
 * @since 18.12.18
 */
@Configuration
@EnableConfigurationProperties(SsoCookieProperties.class)
public class ProxySpringConfiguration {
    @Bean
    public FilterRegistrationBean<BackendForRequestFilter> backendForRequestFilter(
            BackendRegistry backendRegistry,
            ConfigurationService configurationService,
            PathHelper pathHelper
    ) {
        final Supplier<Boolean> configurationProperty =
                configurationService.getConfigurationProperty(com.otr.portal.core.Configuration.class, com.otr.portal.core.Configuration::isSendDiagnosticHeaders);
        var backendForRequestFilter =
                getBackendForRequestFilter(backendRegistry, configurationService, configurationProperty, pathHelper);
        FilterRegistrationBean<BackendForRequestFilter> filterRegistrationBean =
                new FilterRegistrationBean<>(backendForRequestFilter);
        filterRegistrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        filterRegistrationBean.setAsyncSupported(true);
        filterRegistrationBean.addUrlPatterns("/*");

        return filterRegistrationBean;
    }

    /**
     * Запрос проксируется, если {@link BackendForRequestFilter} нашёл для него бэкенд:
     * по этому же признаку {@link HttpProxyFilter} не обслуживает запрос локально.
     */
    @Bean
    public ProxiedRequestMatcher proxiedRequestMatcher() {
        return request ->
                request.getAttribute(BACKEND_ATTRIBUTE_NAME) != null;
    }

    /**
     * Заменяет фильтр из автоконфигурации Spring Boot, которая отступает при наличии своего {@code FormContentFilter}.
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "spring.mvc.formcontent.filter.enabled", matchIfMissing = true)
    public OrderedFormContentFilter formContentFilter(ProxiedRequestMatcher proxiedRequestMatcher) {
        return new ProxyAwareFormContentFilter(proxiedRequestMatcher);
    }

    private static @NonNull BackendForRequestFilter getBackendForRequestFilter(
            BackendRegistry backendRegistry,
            ConfigurationService configurationService,
            Supplier<Boolean> configurationProperty,
            PathHelper pathHelper
    ) {
        return new BackendForRequestFilter(backendRegistry,
                configurationProperty,
                configurationService,
                pathHelper
        );
    }

}
