package com.otr.portal.command;

import com.otr.portal.command.model.CommandContext;
import com.otr.portal.command.model.CommandResilienceHolder;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Декоратор: навешивает обвязку Resilience4j (TimeLimiter, Bulkhead, CircuitBreaker, Retry)
 * на реактивный вызов {@link Mono}.
 *
 * @author alexraspopin
 * @since 04.02.2026
 */
@Component
@RequiredArgsConstructor
public class CommandResilienceDecorator {
    private final CommandConfigRegistry commandConfigRegistry;

    /**
     * Гарантирует наличие конфига Resilience4j и декорирует Mono.
     * <p>
     * Если конфиг для команды ещё не зарегистрирован - создаёт его из переданного контекста.
     * Если уже существует - использует существующий.
     *
     * @return декорированный Mono
     */
    <T> Mono<T> decorate(Mono<T> action, CommandContext commandContext) {
        var holder = commandConfigRegistry.getOrCreate(commandContext);
        return applyOperators(action, holder);
    }

    private <T> Mono<T> applyOperators(Mono<T> action, CommandResilienceHolder holder) {
        return action
                .transformDeferred(RetryOperator.of(holder.getRetry()))
                .transformDeferred(CircuitBreakerOperator.of(holder.getCircuitBreaker()))
                .transformDeferred(TimeLimiterOperator.of(holder.getTimeLimiter()))
                .transformDeferred(BulkheadOperator.of(holder.getBulkhead()));
    }

}
