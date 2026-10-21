package com.otr.portal.command;

import com.otr.portal.command.exception.RetryableException;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Конфигурация реестров Resilience4j с привязкой метрик к MeterRegistry.
 *
 * @author alexraspopin
 * @since 04.02.2026
 */
@Slf4j
@Configuration
public class ResilienceRegistryConfig {

    @Bean
    public BulkheadRegistry bulkheadRegistry(MeterRegistry meterRegistry) {
        BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(BulkheadConfig.ofDefaults());
        TaggedBulkheadMetrics
                .ofBulkheadRegistry(bulkheadRegistry)
                .bindTo(meterRegistry);

        return bulkheadRegistry;
    }

    @Bean
    public TimeLimiterRegistry timeLimiterRegistry(MeterRegistry meterRegistry) {
        TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.of(TimeLimiterConfig.ofDefaults());

        TaggedTimeLimiterMetrics
                .ofTimeLimiterRegistry(timeLimiterRegistry)
                .bindTo(meterRegistry);

        return timeLimiterRegistry;
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meterRegistry) {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .slidingWindowSize(10)
                .build();
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);

        TaggedCircuitBreakerMetrics
                .ofCircuitBreakerRegistry(circuitBreakerRegistry)
                .bindTo(meterRegistry);

        return circuitBreakerRegistry;
    }

    @Bean
    public RetryRegistry retryRegistry(MeterRegistry meterRegistry) {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .retryExceptions(RetryableException.class)
                .build();
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);

        TaggedRetryMetrics
                .ofRetryRegistry(retryRegistry)
                .bindTo(meterRegistry);

        return retryRegistry;
    }
}
