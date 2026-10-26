package com.otr.portal.proxy;

import com.otr.portal.elk.AccessLogEnricher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.otr.portal.hosts.filter.BackendForRequestFilter.BACKEND_NAME_ATTRIBUTE_NAME;

/**
 * @author pobedenniy.alexey
 * @since 19.12.18
 */
@Configuration
@ConditionalOnClass(name = "com.otr.portal.elk.AccessLogEnricher")
public class HostsAccessLogSpringConfiguration {
    @Bean
    public com.otr.portal.elk.AccessLogEnricher backendAccessLogEnricher() {
        return new AccessLogEnricher() {
            @Override
            public String addToPattern() {
                return "\"backend\": \"%requestAttribute{" + BACKEND_NAME_ATTRIBUTE_NAME + "}\",\n";
            }
        };
    }
}
