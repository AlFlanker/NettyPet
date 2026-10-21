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

/** Настраивает политику команд и определяет, какие результаты означают отказ целевого сервера. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProxyCommandProperties.class)
@Import({ResilienceRegistryConfig.class, CommandConfigRegistry.class, CommandResilienceDecorator.class})
public class ProxyCommandConfiguration {
    /** Задаёт общую группу команд прокси с ограничением конкуренции и классификацией отказов. */
    @Bean(destroyMethod = "close")
    @DependsOn("proxyEngine") // Отменяем команды до остановки транспорта, которому принадлежат их I/O-ресурсы.
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
                // Общая длительность обмена зависит от клиента и размера тела. В отличие от таймаута
                // заголовков целевого сервера, её истечение само по себе не доказывает отказ сервера.
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
                // Прочитанное тело Servlet-запроса нельзя воспроизвести; повтор команды запрещён.
                .maxAttempts(1)
                .replayable(false)
                .build();
        return new ProxyCommands(registry, decorator, policy);
    }

    /** Связывает транспорт с политикой команды и HTTP-преобразованием отказов до передачи владения. */
    @Bean
    ProxyRequestHandler proxyRequestHandler(ProxyEngine engine, ProxyCommands proxyCommands) {
        return new ProxyRequestHandler(engine, proxyCommands);
    }
}
