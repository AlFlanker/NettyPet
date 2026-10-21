package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.exception.RetryableException;
import com.otr.portal.command.execution.CancellationReason;
import com.otr.portal.command.execution.CommandTask;
import com.otr.portal.command.model.CommandContext;
import com.otr.portal.command.model.CommandResilienceHolder;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Проверяет, что резервная обработка ошибки имеет отдельный жизненный цикл от защищённой операции. */
@Timeout(10)
class FallbackContractTest {
    private static final Duration ORIGINAL_TIMEOUT = Duration.ofMillis(60);

    /** Резервная обработка может продолжаться после таймаута, пока исходная операция ещё держит ресурсы. */
    @Test
    void resourceFallbackOutlivesTheDeadlineWhileOriginalCleanupStillOwnsItsPermit() throws Exception {
        try (Fixture f = new Fixture(SourceKind.RESOURCE)) {
            CompletableFuture<String> result = f.command().execute().toFuture();
            f.awaitFallback();
            assertInstanceOf(TimeoutException.class, f.fallbackCause.get());
            assertTrue(f.sourceCancelled.await(3, TimeUnit.SECONDS));
            assertEquals(CancellationReason.TIMEOUT, f.resourceCancelReason.get());
            assertEquals(1, f.sourceCancelCalls.get());
            assertEquals(0, f.available());
            assertThrows(IllegalStateException.class, f.registry::clearAllCaches);

            // Если бы исходный таймаут распространялся на резервную обработку, она была бы отменена к этому моменту.
            assertFalse(f.fallbackCancelled.await(ORIGINAL_TIMEOUT.toMillis() * 3, TimeUnit.MILLISECONDS));
            assertFalse(result.isDone());
            assertEquals(1, f.starts.get(), "The original operation is never restarted by fallback");
            assertEquals(1, f.fallbacks.get());
            assertEquals(0, f.available());

            f.resourceCompletion.complete("original cleanup finished");
            assertEquals(1, f.available());
            assertDoesNotThrow(f.registry::clearAllCaches);
            assertFalse(result.isDone(), "Releasing original resources does not complete fallback");
            assertEquals(0, f.fallbackCancelCalls.get());

            f.fallbackSink.get().success("fallback value");
            assertEquals("fallback value", result.get(3, TimeUnit.SECONDS));
            assertEquals(1, f.starts.get());
            assertEquals(1, f.sourceCancelCalls.get());
        }
    }

    /** Исходные лимиты не распространяются на резервный Mono и не определяют момент его завершения. */
    @Test
    void simpleFallbackHasNoOriginalDeadlineOrBulkheadAndCompletesIndependently() throws Exception {
        try (Fixture f = new Fixture(SourceKind.SIMPLE)) {
            CompletableFuture<String> result = f.command().execute().toFuture();
            f.awaitFallback();
            assertInstanceOf(TimeoutException.class, f.fallbackCause.get());
            assertTrue(f.sourceCancelled.await(3, TimeUnit.SECONDS));
            assertEquals(1, f.sourceCancelCalls.get());
            f.awaitOriginalRelease();
            assertEquals(1, f.available(), "Ordinary Mono cancellation finishes the original operation");
            assertDoesNotThrow(f.registry::clearAllCaches);
            assertFalse(f.fallbackCancelled.await(ORIGINAL_TIMEOUT.toMillis() * 3, TimeUnit.MILLISECONDS));
            assertFalse(result.isDone());
            assertEquals(1, f.starts.get());
            f.fallbackSink.get().success("fallback value");
            assertEquals("fallback value", result.get(3, TimeUnit.SECONDS));
            assertEquals(1, f.fallbacks.get());
            assertEquals(1, f.sourceCancelCalls.get());
        }
    }

    /** Отмена подписчика останавливает резервный источник, не дублируя отмену исходной операции. */
    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void downstreamCancellationCancelsFallbackWithoutCancellingOriginalTwice(SourceKind sourceKind) throws Exception {
        try (Fixture f = new Fixture(sourceKind)) {
            var subscription = f.command().execute().subscribe();
            f.awaitFallback();
            assertTrue(f.sourceCancelled.await(3, TimeUnit.SECONDS));
            assertEquals(1, f.sourceCancelCalls.get());
            subscription.dispose();
            subscription.dispose();
            assertTrue(f.fallbackCancelled.await(3, TimeUnit.SECONDS));
            assertEquals(1, f.fallbackCancelCalls.get());
            assertEquals(1, f.sourceCancelCalls.get(), "The timeout has already cancelled the original source");
            assertEquals(1, f.starts.get());
            assertEquals(1, f.fallbacks.get());
            if (sourceKind == SourceKind.RESOURCE) {
                assertEquals(0, f.available());
                assertThrows(IllegalStateException.class, f.registry::clearAllCaches);
                f.resourceCompletion.complete("original cleanup finished");
            }
            f.awaitOriginalRelease();
            assertEquals(1, f.available());
            assertDoesNotThrow(f.registry::clearAllCaches);
        }
    }

    /** Ошибка резервной обработки не повторяет исходную команду и не меняет её учёт в CircuitBreaker. */
    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void retryableFallbackFailureDoesNotRetryTheOriginalOrAddAnotherCircuitSample(SourceKind sourceKind) throws Exception {
        try (Fixture f = new Fixture(sourceKind)) {
            var result = f.command().execute().toFuture();
            f.awaitFallback();
            assertTrue(f.sourceCancelled.await(3, TimeUnit.SECONDS));
            if (sourceKind == SourceKind.RESOURCE) f.resourceCompletion.complete("original cleanup finished");
            f.awaitOriginalRelease();
            assertEquals(1, f.available());
            var fallbackFailure = new RetryableException("Fallback has its own failure policy");
            f.fallbackSink.get().error(fallbackFailure);
            assertSame(fallbackFailure, assertThrows(ExecutionException.class,
                    () -> result.get(3, TimeUnit.SECONDS)).getCause());
            assertEquals(1, f.starts.get(), "Even an opt-in retry policy does not wrap fallback");
            assertEquals(1, f.fallbacks.get());
            assertEquals(1, f.holder.getCircuitBreaker().getMetrics().getNumberOfFailedCalls());
            assertEquals(1, f.holder.getCircuitBreaker().getMetrics().getNumberOfBufferedCalls());
            assertEquals(1, f.sourceCancelCalls.get());
        }
    }

    enum SourceKind { SIMPLE, RESOURCE }

    private static final class Fixture implements AutoCloseable {
        final SourceKind kind;
        final CommandProperties policy = CommandProperties.builder().timeout(ORIGINAL_TIMEOUT)
                .maxConcurrency(1).maxAttempts(3).replayable(true).retryWaitDuration(Duration.ZERO).build();
        final CommandConfigRegistry registry = new CommandConfigRegistry(BulkheadRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
        final CommandResilienceDecorator decorator = new CommandResilienceDecorator(registry);
        final CommandResilienceHolder holder;
        final CompletableFuture<String> resourceCompletion = new CompletableFuture<>();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger sourceCancelCalls = new AtomicInteger();
        final AtomicInteger fallbacks = new AtomicInteger();
        final AtomicInteger fallbackCancelCalls = new AtomicInteger();
        final AtomicReference<CancellationReason> resourceCancelReason = new AtomicReference<>();
        final AtomicReference<Throwable> fallbackCause = new AtomicReference<>();
        final AtomicReference<MonoSink<String>> fallbackSink = new AtomicReference<>();
        final CountDownLatch sourceCancelled = new CountDownLatch(1);
        final CountDownLatch fallbackStarted = new CountDownLatch(1);
        final CountDownLatch fallbackCancelled = new CountDownLatch(1);

        Fixture(SourceKind kind) {
            this.kind = kind;
            var context = CommandContext.builder().commandName("fallback-contract").properties(policy).build();
            try (var lease = registry.acquire(context)) { holder = lease.holder(); }
        }

        Command<String> command() {
            if (kind == SourceKind.SIMPLE) {
                return new AbstractResilienceCommand<>(decorator, policy) {
                    @Override protected String getCommandName() { return "fallback-contract"; }
                    @Override protected Mono<String> doExecute() {
                        starts.incrementAndGet();
                        return Mono.<String>never().doOnCancel(Fixture.this::sourceCancelled);
                    }
                    @Override protected Mono<String> fallbackOnError(Throwable failure) { return fallback(failure); }
                    @Override protected void logResilienceError(Throwable failure, String name) { }
                };
            }
            return new AbstractResourceCommand<>(decorator, policy) {
                @Override protected String getCommandName() { return "fallback-contract"; }
                @Override protected CommandTask<String> doStart() {
                    starts.incrementAndGet();
                    return new CommandTask<>() {
                        @Override public CompletionStage<String> completion() { return resourceCompletion; }
                        @Override public void cancel(CancellationReason reason) {
                            resourceCancelReason.compareAndSet(null, reason);
                            sourceCancelled();
                        }
                    };
                }
                @Override protected Mono<String> fallbackOnError(Throwable failure) { return fallback(failure); }
            };
        }

        private void sourceCancelled() {
            sourceCancelCalls.incrementAndGet();
            sourceCancelled.countDown();
        }

        Mono<String> fallback(Throwable failure) {
            fallbacks.incrementAndGet();
            fallbackCause.set(failure);
            return Mono.create(sink -> {
                sink.onCancel(() -> {
                    fallbackCancelCalls.incrementAndGet();
                    fallbackCancelled.countDown();
                });
                fallbackSink.set(sink);
                fallbackStarted.countDown();
            });
        }

        void awaitFallback() throws InterruptedException {
            assertTrue(fallbackStarted.await(3, TimeUnit.SECONDS));
        }

        void awaitOriginalRelease() {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertEquals(1, available());
                // Возврат разрешения опережает отдельный обработчик освобождения ссылки на поколение конфигурации.
                assertDoesNotThrow(registry::clearAllCaches);
            });
        }

        int available() { return holder.getBulkhead().getMetrics().getAvailableConcurrentCalls(); }

        @Override public void close() {
            decorator.close();
            resourceCompletion.complete("fixture cleanup");
        }
    }
}
