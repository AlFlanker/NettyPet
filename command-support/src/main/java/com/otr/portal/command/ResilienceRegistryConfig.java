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

/**
 * Необязательная интеграция со Spring: создаёт отсутствующие реестры Resilience4j
 * и отдельно управляет привязкой метрик, в том числе для пользовательских реестров.
 */
@Configuration(proxyBeanMethods = false)
public class ResilienceRegistryConfig {
    /** Предоставляет реестр лимитов параллелизма, если приложение не передало свой. */
    @Bean
    @ConditionalOnMissingBean
    public BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.ofDefaults();
    }

    /** Предоставляет реестр ограничений времени, сохраняя пользовательский bean при его наличии. */
    @Bean
    @ConditionalOnMissingBean
    public TimeLimiterRegistry timeLimiterRegistry() {
        return TimeLimiterRegistry.ofDefaults();
    }

    /** Предоставляет реестр CircuitBreaker для учёта результатов и временного запрета вызовов. */
    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    /** Создаёт реестр повторов с одной попыткой по умолчанию; конкретная команда может явно разрешить больше. */
    @Bean
    @ConditionalOnMissingBean
    public RetryRegistry retryRegistry() {
        return RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    /**
     * Привязывает метрики фактически внедрённых реестров независимо от способа их создания.
     * Без MeterRegistry возвращает пустую привязку, чтобы метрики оставались необязательными.
     */
    @Bean(destroyMethod = "close")
    RegistryMetricsBinding commandRegistryMetrics(ObjectProvider<MeterRegistry> metrics,
                                                  BulkheadRegistry bulkheads, TimeLimiterRegistry timers,
                                                  CircuitBreakerRegistry breakers, RetryRegistry retries) {
        MeterRegistry meters = metrics.getIfAvailable();
        return meters == null ? new RegistryMetricsBinding()
                : new RegistryMetricsBinding(meters, bulkheads, timers, breakers, retries);
    }
}
