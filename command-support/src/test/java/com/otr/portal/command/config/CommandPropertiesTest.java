package com.otr.portal.command.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import com.otr.portal.command.execution.CommandCancelledException;
import com.otr.portal.command.execution.CommandNotExecutedException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandPropertiesTest {
    @Test void retryRequiresExplicitReplayability() {
        var defaults = CommandProperties.defaultProperties();
        assertEquals(1, defaults.getMaxAttempts());
        assertFalse(defaults.isReplayable());
        assertThrows(IllegalArgumentException.class, () -> CommandProperties.builder().maxAttempts(2).build());
        assertEquals(2, CommandProperties.builder().maxAttempts(2).replayable(true).build().getMaxAttempts());
    }

    @Test void invalidTimeAndConcurrencyCannotReachExecution() {
        assertThrows(IllegalArgumentException.class, () -> CommandProperties.builder().timeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class, () -> CommandProperties.builder().timeout(Duration.ofNanos(1)).build());
        assertThrows(IllegalArgumentException.class, () -> CommandProperties.builder().timeout(Duration.ofSeconds(-1)).build());
        assertThrows(IllegalArgumentException.class, () -> CommandProperties.builder().timeout(Duration.ofDays(999_999)).build());
        assertThrows(IllegalArgumentException.class, () -> CommandProperties.builder().timeout(Duration.ofSeconds(Long.MAX_VALUE)).build());
        assertThrows(IllegalArgumentException.class, () -> CommandProperties.builder().maxConcurrency(0).build());
        assertThrows(IllegalArgumentException.class, () -> CommandProperties.builder().maxAttempts(0).build());
        assertThrows(IllegalArgumentException.class, () -> CommandProperties.builder().retryWaitDuration(Duration.ofSeconds(-1)).build());
        assertThrows(IllegalArgumentException.class, () -> CommandProperties.builder().retryWaitDuration(Duration.ofDays(999_999)).build());
        assertThrows(NullPointerException.class, () -> CommandProperties.builder().timeout(null).build());
        assertThrows(NullPointerException.class, () -> CommandProperties.builder().circuitBreakerConfig(null).build());
    }

    @Test void perCommandCircuitBreakerConfigAndPredicatesArePreserved() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(3).slidingWindowSize(7).failureRateThreshold(75)
                .recordResult(result -> result.equals("bad")).build();
        var properties = CommandProperties.builder().circuitBreakerConfig(config).build();
        assertSame(config, properties.getCircuitBreakerConfig());
        assertTrue(properties.getCircuitBreakerConfig().getRecordResultPredicate().test("bad"));
        assertEquals(properties, properties.toBuilder().build());
        assertEquals(CommandProperties.defaultProperties(), CommandProperties.defaultProperties());
    }

    @Test void defaultBreakerIgnoresLocalCancellationButRecordsTimeout() {
        var config = CommandProperties.defaultProperties().getCircuitBreakerConfig();
        assertTrue(config.getIgnoreExceptionPredicate().test(new CommandCancelledException("cancelled")));
        assertTrue(config.getIgnoreExceptionPredicate().test(new CommandNotExecutedException("not executed")));
        assertTrue(config.getIgnoreExceptionPredicate().test(new java.util.concurrent.CancellationException()));
        assertFalse(config.getIgnoreExceptionPredicate().test(new java.util.concurrent.TimeoutException()));
        assertTrue(config.getRecordExceptionPredicate().test(new java.util.concurrent.TimeoutException()));
    }
}
