package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.model.CommandContext;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandConfigRegistryTest {
    final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    final TimeLimiterRegistry timers = TimeLimiterRegistry.ofDefaults();
    final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    final RetryRegistry retries = RetryRegistry.ofDefaults();
    final CommandConfigRegistry registry = new CommandConfigRegistry(bulkheads, timers, breakers, retries);

    static CommandContext context(String name) {
        return new CommandContext(name, CommandProperties.defaultProperties());
    }

    @Test void leaseKeepsPolicyGenerationStableAndCannotBeClosedTwice() {
        var first = registry.acquire(context("first"));
        var second = registry.acquire(context("first"));
        assertSame(first.holder(), second.holder());
        assertThrows(IllegalStateException.class, () -> registry.clearCache("first"));
        first.close();
        first.close();
        assertThrows(IllegalStateException.class, registry::clearAllCaches);
        var old = second.holder();
        second.close();
        registry.clearAllCaches();
        try (var next = registry.acquire(context("first"))) { assertNotSame(old, next.holder()); }
    }

    @Test void clearingIsAtomicAndDoesNotRemoveUnmanagedPolicies() {
        bulkheads.bulkhead("external");
        try (var ignored = registry.acquire(context("owned-idle"))) { }
        var busy = registry.acquire(context("owned-busy"));
        var idle = registry.getOrCreate(context("owned-idle"));
        assertThrows(IllegalStateException.class, () -> registry.clearCache("owned-"));
        assertSame(idle, registry.getOrCreate(context("owned-idle")));
        registry.clearCache("owned-idle");
        assertTrue(bulkheads.find("owned-idle").isEmpty());
        assertTrue(bulkheads.find("owned-busy").isPresent());
        busy.close();
        registry.clearAllCaches();
        assertTrue(bulkheads.find("owned-busy").isEmpty());
        assertTrue(bulkheads.find("external").isPresent());
    }

    @Test void rejectsPolicyChangesUntilIdlePolicyIsExplicitlyCleared() {
        var oldContext = context("policy");
        try (var ignored = registry.acquire(oldContext)) { }
        var nextContext = new CommandContext("policy", CommandProperties.builder().maxConcurrency(1).build());
        assertThrows(IllegalArgumentException.class, () -> registry.acquire(nextContext));
        registry.clearCache("policy");
        try (var updated = registry.acquire(nextContext)) {
            assertEquals(1, updated.holder().getBulkhead().getBulkheadConfig().getMaxConcurrentCalls());
        }
    }

    @Test void createsConfiguredPoliciesWithoutReplacingExternalEntries() {
        var cbConfig = CircuitBreakerConfig.custom().minimumNumberOfCalls(2).slidingWindowSize(3).build();
        var properties = CommandProperties.builder().maxConcurrency(7).timeout(Duration.ofMillis(250))
                .maxAttempts(3).replayable(true).retryWaitDuration(Duration.ZERO).circuitBreakerConfig(cbConfig).build();
        try (var lease = registry.acquire(new CommandContext("configured", properties))) {
            var holder = lease.holder();
            assertEquals(7, holder.getBulkhead().getBulkheadConfig().getMaxConcurrentCalls());
            assertEquals(Duration.ZERO, holder.getBulkhead().getBulkheadConfig().getMaxWaitDuration());
            assertEquals(Duration.ofMillis(250), holder.getTimeLimiter().getTimeLimiterConfig().getTimeoutDuration());
            assertSame(cbConfig, holder.getCircuitBreaker().getCircuitBreakerConfig());
            assertEquals(3, holder.getRetry().getRetryConfig().getMaxAttempts());
        }
        bulkheads.bulkhead("external");
        assertThrows(IllegalStateException.class, () -> registry.acquire(context("external")));
    }

    @Test void rejectsInvalidContextsAndPrefix() {
        assertThrows(IllegalArgumentException.class, () -> context(" "));
        assertThrows(NullPointerException.class, () -> new CommandContext("name", null));
        assertThrows(NullPointerException.class, () -> registry.acquire(null));
        assertThrows(NullPointerException.class, () -> registry.clearCache(null));
    }
}
