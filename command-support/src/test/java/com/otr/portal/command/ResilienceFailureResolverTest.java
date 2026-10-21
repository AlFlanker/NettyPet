package com.otr.portal.command;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.MaxRetriesExceededException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienceFailureResolverTest {

    @Test
    @DisplayName("TimeoutException классифицируется как TIMEOUT")
    void shouldResolveTimeout() {
        assertThat(ResilienceFailureResolver.resolve(new TimeoutException("deadline")))
                .isEqualTo(ResilienceFailureKind.TIMEOUT);
    }

    @Test
    @DisplayName("TimeoutException в cause классифицируется как TIMEOUT")
    void shouldResolveWrappedTimeout() {
        RuntimeException wrapped = new RuntimeException("wrapper", new TimeoutException("deadline"));
        assertThat(ResilienceFailureResolver.resolve(wrapped))
                .isEqualTo(ResilienceFailureKind.TIMEOUT);
    }

    @Test
    @DisplayName("CallNotPermittedException классифицируется как CIRCUIT_OPEN")
    void shouldResolveCircuitOpen() {
        CircuitBreaker circuitBreaker = CircuitBreaker.of("test-cb", CircuitBreakerConfig.ofDefaults());
        CallNotPermittedException error = CallNotPermittedException.createCallNotPermittedException(circuitBreaker);
        assertThat(ResilienceFailureResolver.resolve(error))
                .isEqualTo(ResilienceFailureKind.CIRCUIT_OPEN);
    }

    @Test
    @DisplayName("BulkheadFullException классифицируется как BULKHEAD_FULL")
    void shouldResolveBulkheadFull() {
        Bulkhead bulkhead = Bulkhead.of("test-bh", BulkheadConfig.ofDefaults());
        BulkheadFullException error = BulkheadFullException.createBulkheadFullException(bulkhead);
        assertThat(ResilienceFailureResolver.resolve(error))
                .isEqualTo(ResilienceFailureKind.BULKHEAD_FULL);
    }

    @Test
    @DisplayName("MaxRetriesExceededException классифицируется как RETRY_EXHAUSTED")
    void shouldResolveRetryExhausted() {
        Retry retry = Retry.of("test-retry", RetryConfig.ofDefaults());
        MaxRetriesExceededException error = MaxRetriesExceededException.createMaxRetriesExceededException(retry);
        assertThat(ResilienceFailureResolver.resolve(error))
                .isEqualTo(ResilienceFailureKind.RETRY_EXHAUSTED);
    }

    @Test
    @DisplayName("Произвольное исключение классифицируется как OTHER")
    void shouldResolveOther() {
        assertThat(ResilienceFailureResolver.resolve(new IllegalStateException("fail")))
                .isEqualTo(ResilienceFailureKind.OTHER);
    }

    @Test
    @DisplayName("null классифицируется как OTHER")
    void shouldResolveNullAsOther() {
        assertThat(ResilienceFailureResolver.resolve(null))
                .isEqualTo(ResilienceFailureKind.OTHER);
    }
}
