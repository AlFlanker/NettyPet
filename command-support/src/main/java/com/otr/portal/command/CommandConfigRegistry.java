package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.exception.RetryableException;
import com.otr.portal.command.model.CommandContext;
import com.otr.portal.command.model.CommandResilienceHolder;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Owns named policies; configuration cannot be replaced until all invocations finish cleanup.
 * Resilience4j registry listeners must be nonblocking and must not throw; external callers
 * must not replace or remove this registry's named policies.
 */
@Component
@RequiredArgsConstructor
public class CommandConfigRegistry {
    private final BulkheadRegistry bulkheadRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final Map<String, Entry> commandConfigs = new HashMap<>();

    /**
     * Acquires one lifecycle reference, including admission, retries, cancellation and cleanup.
     * The caller closes the lease only after the operation has released its resources.
     */
    public synchronized Lease acquire(CommandContext context) {
        Objects.requireNonNull(context, "context");
        Entry entry = resolve(context);
        entry.active++;
        return new Lease(entry);
    }

    /** Compatibility lookup. Executions must use {@link #acquire(CommandContext)} instead. */
    synchronized CommandResilienceHolder getOrCreate(CommandContext context) {
        return resolve(Objects.requireNonNull(context, "context")).holder;
    }

    /** Atomically clears only policies owned by this registry, provided none is in use. */
    public synchronized void clearAllCaches() {
        clearMatching(name -> true);
    }

    /**
     * Atomically clears owned policies with the given prefix. Fails without changing anything
     * when a matching operation is still active, even if its result already timed out.
     */
    public synchronized void clearCache(String startsWith) {
        Objects.requireNonNull(startsWith, "startsWith");
        clearMatching(name -> name.startsWith(startsWith));
    }

    private Entry resolve(CommandContext context) {
        Entry existing = commandConfigs.get(context.getCommandName());
        if (existing != null) {
            if (!existing.properties.equals(context.getProperties())) {
                throw new IllegalArgumentException("Policy for command '" + context.getCommandName()
                        + "' already exists; clear it while idle before changing its configuration");
            }
            return existing;
        }
        String name = context.getCommandName();
        // Never silently adopt a limiter created outside this registry: its policy could differ.
        if (bulkheadRegistry.find(name).isPresent() || timeLimiterRegistry.find(name).isPresent()
                || circuitBreakerRegistry.find(name).isPresent() || retryRegistry.find(name).isPresent()) {
            throw new IllegalStateException("Resilience registry already contains unmanaged command '" + name + "'");
        }
        CommandProperties properties = context.getProperties();
        var holder = CommandResilienceHolder.builder()
                .bulkhead(bulkheadRegistry.bulkhead(name, BulkheadConfig.custom()
                        .maxConcurrentCalls(properties.getMaxConcurrency()).maxWaitDuration(Duration.ZERO).build()))
                .timeLimiter(timeLimiterRegistry.timeLimiter(name, TimeLimiterConfig.custom()
                        .timeoutDuration(properties.getTimeout()).cancelRunningFuture(true).build()))
                .circuitBreaker(circuitBreakerRegistry.circuitBreaker(name, properties.getCircuitBreakerConfig()))
                .retry(retryRegistry.retry(name, RetryConfig.custom()
                        .maxAttempts(properties.getMaxAttempts())
                        .waitDuration(properties.getRetryWaitDuration())
                        .retryExceptions(RetryableException.class).build()))
                .build();
        Entry created = new Entry(properties, holder);
        commandConfigs.put(name, created);
        return created;
    }

    private void clearMatching(Predicate<String> matches) {
        commandConfigs.forEach((name, entry) -> {
            if (matches.test(name) && entry.active != 0) {
                throw new IllegalStateException("Command '" + name + "' still has active operations");
            }
        });
        var names = commandConfigs.keySet().stream().filter(matches).toList();
        names.forEach(name -> {
            bulkheadRegistry.remove(name);
            timeLimiterRegistry.remove(name);
            circuitBreakerRegistry.remove(name);
            retryRegistry.remove(name);
            commandConfigs.remove(name);
        });
    }

    /** Idempotent reference to a stable policy generation. */
    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean closed;

        private Lease(Entry entry) { this.entry = entry; }

        public CommandResilienceHolder holder() { return entry.holder; }

        @Override
        public void close() {
            synchronized (CommandConfigRegistry.this) {
                if (closed) return;
                closed = true;
                entry.active--;
            }
        }
    }

    private static final class Entry {
        private final CommandProperties properties;
        private final CommandResilienceHolder holder;
        private int active;

        private Entry(CommandProperties properties, CommandResilienceHolder holder) {
            this.properties = properties;
            this.holder = holder;
        }
    }
}
