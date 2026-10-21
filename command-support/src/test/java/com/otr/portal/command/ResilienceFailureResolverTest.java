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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет классификацию причин отказа, включая вложенные и циклические цепочки исключений. */
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
    void shouldResolveNestedAsyncWrappers() {
        Throwable failure = new CompletionException(new ExecutionException(
                new RuntimeException("adapter", new TimeoutException("deadline"))));
        assertThat(ResilienceFailureResolver.resolve(failure)).isEqualTo(ResilienceFailureKind.TIMEOUT);
    }

    @Test
    void shouldStopAtCyclicCauseChain() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertThat(ResilienceFailureResolver.resolve(first)).isEqualTo(ResilienceFailureKind.OTHER);
    }

    @Test
    void shouldBoundPathologicalCauseChains() {
        Throwable failure = new TimeoutException("outside diagnostic depth");
        for (int depth = 0; depth < 100; depth++) failure = new RuntimeException("wrapper", failure);
        assertThat(ResilienceFailureResolver.resolve(failure)).isEqualTo(ResilienceFailureKind.OTHER);
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
