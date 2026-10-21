package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.model.CommandContext;
import com.otr.portal.command.model.CommandResilienceHolder;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Централизованный реестр конфигураций Resilience4j.
 * По имени команды выдаёт {@link CommandResilienceHolder} с Bulkhead, TimeLimiter, CircuitBreaker и Retry
 * для декорирования вызовов.
 *
 * @author alexraspopin
 * @since 04.02.2026
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandConfigRegistry {
    private final BulkheadRegistry bulkheadRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    private final Map<String, CommandResilienceHolder> commandConfigs = new ConcurrentHashMap<>();

    /**
     * Возвращает конфиг по имени команды; при отсутствии атомарно создаёт его с переданными свойствами.
     * Потокобезопасно: при одновременном запросе одного и того же имени конфиг создаётся один раз.
     * @return актуальный конфиг
     */
    CommandResilienceHolder getOrCreate(CommandContext commandContext) {
        return commandConfigs
                .computeIfAbsent(commandContext.getCommandName(), key ->
                        buildHolder(key, commandContext.getProperties()));
    }

    /**
     * Очищает весь реестр: все конфиги команд и все записи в Resilience4j-реестрах
     * (Bulkhead, TimeLimiter, CircuitBreaker, Retry).
     */
    public void clearAllCaches() {
        commandConfigs.clear();

        bulkheadRegistry.getAllBulkheads().stream()
                .map(Bulkhead::getName)
                .toList()
                .forEach(bulkheadRegistry::remove);
        timeLimiterRegistry.getAllTimeLimiters().stream()
                .map(TimeLimiter::getName)
                .toList()
                .forEach(timeLimiterRegistry::remove);
        circuitBreakerRegistry.getAllCircuitBreakers().stream()
                .map(CircuitBreaker::getName)
                .toList()
                .forEach(circuitBreakerRegistry::remove);
        retryRegistry.getAllRetries().stream()
                .map(Retry::getName)
                .toList()
                .forEach(retryRegistry::remove);

        log.info("Resilience4j регистр очищен");
    }

    /**
     * Удаляет конфиг и записи Resilience4j только для команды начинающихся с <b>startWith</b>.
     *
     * @param startWith имя команды
     */
    public void clearCache(String startWith) {
        commandConfigs.keySet()
                .stream()
                .filter(command ->
                        command.startsWith(startWith))
                .forEach(commandConfigs::remove);


        bulkheadRegistry.getAllBulkheads().stream()
                .map(Bulkhead::getName)
                .filter(name -> name.startsWith(startWith))
                .toList()
                .forEach(bulkheadRegistry::remove);
        timeLimiterRegistry.getAllTimeLimiters().stream()
                .map(TimeLimiter::getName)
                .filter(name -> name.startsWith(startWith))
                .toList()
                .forEach(timeLimiterRegistry::remove);
        circuitBreakerRegistry.getAllCircuitBreakers().stream()
                .map(CircuitBreaker::getName)
                .filter(name -> name.startsWith(startWith))
                .toList()
                .forEach(circuitBreakerRegistry::remove);
        retryRegistry.getAllRetries().stream()
                .map(Retry::getName)
                .filter(name -> name.startsWith(startWith))
                .toList()
                .forEach(retryRegistry::remove);

        log.info("Resilience4j для команд(-ы) \"{}\" регистр очищен", startWith);
    }

    /**
     * Собирает конфигурацию Resilience4j для команды (без записи в map).
     *
     * @param commandKey имя команды
     * @param properties настройки (timeout, maxConcurrency)
     * @return новый {@link CommandResilienceHolder}
     */
    private CommandResilienceHolder buildHolder(String commandKey, CommandProperties properties) {
        Bulkhead bulkhead = resolveBulkhead(commandKey, properties);
        TimeLimiter timeLimiter = resolveTimeLimiter(commandKey, properties);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(commandKey);
        Retry retry = retryRegistry.retry(commandKey);

        return CommandResilienceHolder.builder()
                .bulkhead(bulkhead)
                .timeLimiter(timeLimiter)
                .circuitBreaker(circuitBreaker)
                .retry(retry)
                .build();
    }

    private Bulkhead resolveBulkhead(String commandKey, CommandProperties properties) {
        if (properties.getMaxConcurrency() > 0) {
            bulkheadRegistry.remove(commandKey);
            BulkheadConfig config = BulkheadConfig.custom()
                    .maxConcurrentCalls(properties.getMaxConcurrency())
                    .maxWaitDuration(Duration.ZERO)
                    .build();
            return bulkheadRegistry.bulkhead(commandKey, config);
        }

        return bulkheadRegistry.bulkhead(commandKey);
    }

    private TimeLimiter resolveTimeLimiter(String commandKey, CommandProperties properties) {
        if (properties.getTimeout() != null) {
            timeLimiterRegistry.remove(commandKey);
            TimeLimiterConfig config = TimeLimiterConfig.custom()
                    .timeoutDuration(properties.getTimeout())
                    .cancelRunningFuture(true)
                    .build();
            return timeLimiterRegistry.timeLimiter(commandKey, config);
        }

        return timeLimiterRegistry.timeLimiter(commandKey);
    }

}
