package com.otr.portal.command;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Optional Spring configuration; registry creation and metrics lifecycle are independent. */
@Configuration(proxyBeanMethods = false)
public class ResilienceRegistryConfig {
    @Bean
    @ConditionalOnMissingBean
    public BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.ofDefaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public TimeLimiterRegistry timeLimiterRegistry() {
        return TimeLimiterRegistry.ofDefaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryRegistry retryRegistry() {
        return RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    @Bean(destroyMethod = "close")
    RegistryMetricsBinding commandRegistryMetrics(ObjectProvider<MeterRegistry> metrics,
                                                  BulkheadRegistry bulkheads, TimeLimiterRegistry timers,
                                                  CircuitBreakerRegistry breakers, RetryRegistry retries) {
        MeterRegistry meters = metrics.getIfAvailable();
        return meters == null ? new RegistryMetricsBinding()
                : new RegistryMetricsBinding(meters, bulkheads, timers, breakers, retries);
    }
}
