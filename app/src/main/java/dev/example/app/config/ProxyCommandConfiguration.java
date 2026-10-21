package dev.example.app.config;

import dev.example.app.command.ProxyRequestHandler;
import dev.example.proxy.api.ProxyResult;
import dev.example.proxy.transport.ProxyEngine;
import dev.example.resilience.CommandCancelledException;
import dev.example.resilience.CommandExecutor;
import dev.example.resilience.CommandNotExecutedException;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** Единственное место, где политика команд сопоставляется с результатами proxy. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProxyCommandProperties.class)
public class ProxyCommandConfiguration {
    @Bean(destroyMethod = "close")
    @DependsOn("proxyEngine") // При shutdown сначала отменяем команды, затем останавливаем I/O.
    CommandExecutor<ProxyResult> proxyCommands(ProxyCommandProperties properties) {
        BulkheadConfig bulkhead = BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrent())
                .maxWaitDuration(Duration.ZERO) // Никакого ожидания semaphore на servlet/I/O-потоке.
                .writableStackTraceEnabled(false)
                .build();
        TimeLimiterConfig timeLimiter = TimeLimiterConfig.custom()
                .timeoutDuration(properties.timeout())
                .cancelRunningFuture(true) // Для CompletionStage реальная отмена обеспечивается CommandTask.
                .build();
        CircuitBreakerConfig circuitBreaker = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                // Полное streaming-время зависит от размера тела и медленного клиента.
                // Такие таймауты не доказывают отказ upstream и исключены из его статистики.
                .ignoreExceptions(CommandCancelledException.class, CommandNotExecutedException.class,
                        TimeoutException.class)
                .recordResult(result -> result instanceof ProxyResult proxy &&
                        (proxy.outcome() == ProxyResult.Outcome.UPSTREAM_FAILURE
                                || proxy.outcome() == ProxyResult.Outcome.RESPONSE && proxy.status() >= 500))
                .slowCallDurationThreshold(properties.timeout().plusSeconds(1))
                .writableStackTraceEnabled(false)
                .build();
        return new CommandExecutor<>("proxy", bulkhead, timeLimiter, circuitBreaker);
    }

    @Bean
    ProxyRequestHandler proxyRequestHandler(ProxyEngine engine, CommandExecutor<ProxyResult> proxyCommands) {
        return new ProxyRequestHandler(engine, proxyCommands);
    }
}
