package dev.example.app.config;

import com.otr.portal.command.CommandConfigRegistry;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.ResilienceRegistryConfig;
import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.execution.CommandCancelledException;
import com.otr.portal.command.execution.CommandNotExecutedException;
import dev.example.app.command.ProxyCommands;
import dev.example.app.command.ProxyRequestHandler;
import dev.example.proxy.api.ProxyResult;
import dev.example.proxy.transport.ProxyEngine;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;

/** The application alone defines which transport results indicate upstream failure. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProxyCommandProperties.class)
@Import({ResilienceRegistryConfig.class, CommandConfigRegistry.class, CommandResilienceDecorator.class})
public class ProxyCommandConfiguration {
    @Bean(destroyMethod = "close")
    @DependsOn("proxyEngine") // Cancel commands before stopping their I/O owner.
    ProxyCommands proxyCommands(ProxyCommandProperties properties, CommandConfigRegistry registry,
                                CommandResilienceDecorator decorator) {
        CircuitBreakerConfig circuitBreaker = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                // Total streaming time also depends on the client/body size. Unlike an
                // upstream header timeout, its expiry does not prove backend failure.
                .ignoreExceptions(CommandCancelledException.class, CommandNotExecutedException.class,
                        TimeoutException.class)
                .recordResult(result -> result instanceof ProxyResult proxy &&
                        (proxy.outcome() == ProxyResult.Outcome.UPSTREAM_FAILURE
                                || proxy.outcome() == ProxyResult.Outcome.RESPONSE && proxy.status() >= 500))
                .slowCallDurationThreshold(properties.timeout().plusSeconds(1))
                .writableStackTraceEnabled(false)
                .build();
        CommandProperties policy = CommandProperties.builder()
                .timeout(properties.timeout())
                .maxConcurrency(properties.maxConcurrent())
                .circuitBreakerConfig(circuitBreaker)
                // A Servlet request body cannot be replayed. Never retry this command.
                .maxAttempts(1)
                .replayable(false)
                .build();
        return new ProxyCommands(registry, decorator, policy);
    }

    @Bean
    ProxyRequestHandler proxyRequestHandler(ProxyEngine engine, ProxyCommands proxyCommands) {
        return new ProxyRequestHandler(engine, proxyCommands);
    }
}
