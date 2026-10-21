package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.exception.RetryableException;
import com.otr.portal.command.execution.CancellationReason;
import com.otr.portal.command.execution.CommandCancelledException;
import com.otr.portal.command.execution.CommandExecution;
import com.otr.portal.command.execution.CommandNotExecutedException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.publisher.BaseSubscriber;
import org.reactivestreams.Subscription;

import static org.junit.jupiter.api.Assertions.*;

/** Public API regression tests for admission, delayed cleanup and the total retry deadline. */
@Timeout(10)
class CommandSafetyRegressionTest {
    @Test
    void constructingPublisherDoesNotRunWorkAndEverySubscriptionGetsFreshWork() {
        try (Fixture f = new Fixture(properties(Duration.ofSeconds(3)))) {
            AtomicInteger starts = new AtomicInteger();
            var command = f.simple(() -> Mono.just("attempt-" + starts.incrementAndGet()));
            Mono<String> publisher = command.execute();
            assertEquals(0, starts.get());
            assertEquals("attempt-1", publisher.block(Duration.ofSeconds(3)));
            assertEquals("attempt-2", publisher.block(Duration.ofSeconds(3)));
            assertEquals(2, starts.get());
            assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
        }
    }

    @Test
    void synchronousFactoryExceptionReachesFallbackOnce() {
        try (Fixture f = new Fixture(properties(Duration.ofSeconds(3)))) {
            var original = new IllegalArgumentException("factory failed before returning Mono");
            assertEquals("fallback", f.simple(() -> { throw original; }).execute().block(Duration.ofSeconds(3)));
            assertSame(original, f.lastFallback.get());
            assertEquals(1, f.fallbacks.get());
            assertEquals(1, f.holder.getCircuitBreaker().getMetrics().getNumberOfFailedCalls());
            assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
        }
    }

    @Test
    void fullBulkheadDoesNotCallTheSourceFactory() throws Exception {
        try (Fixture f = new Fixture(properties(Duration.ofSeconds(3)))) {
            ManualTask task = new ManualTask();
            CommandExecution<String> occupied = f.decorator.start(f.context, () -> task);
            AtomicInteger starts = new AtomicInteger();
            assertEquals("fallback", f.simple(() -> {
                starts.incrementAndGet();
                return Mono.just("unexpected");
            }).execute().block(Duration.ofSeconds(3)));
            assertEquals(0, starts.get());
            assertEquals(0, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
            task.completion.complete("cleaned");
            occupied.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void openCircuitDoesNotCallTheSourceFactory() {
        try (Fixture f = new Fixture(properties(Duration.ofSeconds(3)))) {
            f.holder.getCircuitBreaker().transitionToOpenState();
            AtomicInteger starts = new AtomicInteger();
            assertEquals("fallback", f.simple(() -> {
                starts.incrementAndGet();
                return Mono.just("unexpected");
            }).execute().block(Duration.ofSeconds(3)));
            assertEquals(0, starts.get());
            assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
        }
    }

    @Test
    void fallbackOnTimeoutDoesNotReleaseThePermitOrAllowRegistryResetBeforeCleanup() throws Exception {
        try (Fixture f = new Fixture(properties(Duration.ofMillis(80)))) {
            ManualTask task = new ManualTask();
            AtomicInteger starts = new AtomicInteger();
            Mono<String> publisher = f.resource(() -> {
                starts.incrementAndGet();
                return task;
            }).execute();
            assertEquals("fallback", publisher.block(Duration.ofSeconds(3)));
            assertInstanceOf(TimeoutException.class, f.lastFallback.get());
            assertTrue(task.cancelled.await(3, TimeUnit.SECONDS));
            assertEquals(CancellationReason.TIMEOUT, task.reason.get());
            assertEquals(1, task.cancelCalls.get());
            assertEquals(0, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
            assertThrows(IllegalStateException.class, () -> f.registry.clearCache("safety"));
            assertEquals("fallback", publisher.block(Duration.ofSeconds(3)));
            assertEquals(1, starts.get(), "A timed-out task still occupies the bulkhead during cleanup");
            task.completion.complete("cleanup finished");
            // CompletableFuture completion invokes the engine's cleanup observer synchronously.
            assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
            assertDoesNotThrow(() -> f.registry.clearCache("safety"));
        }
    }

    @Test
    void genericCircuitBreakerRecordsTheDeadlineRatherThanTreatingItAsCancellation() throws Exception {
        try (Fixture f = new Fixture(properties(Duration.ofMillis(60)))) {
            ManualTask task = new ManualTask();
            CommandExecution<String> execution = f.decorator.start(f.context, () -> task);
            assertInstanceOf(TimeoutException.class, failure(execution));
            assertTrue(task.cancelled.await(3, TimeUnit.SECONDS));
            assertEquals(1, f.holder.getCircuitBreaker().getMetrics().getNumberOfFailedCalls());
            assertEquals(0, f.holder.getCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls());
            task.completion.complete("cleaned");
            execution.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void callerCancellationThenShutdownCancelsTaskOnlyOnceAndStillWaitsForCleanup() throws Exception {
        try (Fixture f = new Fixture(properties(Duration.ofSeconds(3)))) {
            ManualTask task = new ManualTask();
            CommandExecution<String> execution = f.decorator.start(f.context, () -> task);
            execution.cancel();
            execution.cancel();
            f.decorator.close();
            assertInstanceOf(CommandCancelledException.class, failure(execution));
            assertTrue(task.cancelled.await(3, TimeUnit.SECONDS));
            assertEquals(CancellationReason.CALLER, task.reason.get());
            assertEquals(1, task.cancelCalls.get());
            assertFalse(execution.terminated().toCompletableFuture().isDone());
            assertThrows(IllegalStateException.class, f.registry::clearAllCaches);
            assertEquals(0, f.holder.getCircuitBreaker().getMetrics().getNumberOfBufferedCalls(),
                    "Local caller cancellation is not an upstream failure or success");
            task.completion.complete("cleaned");
            execution.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertDoesNotThrow(f.registry::clearAllCaches);
            AtomicInteger starts = new AtomicInteger();
            var rejected = f.decorator.start(f.context, () -> {
                starts.incrementAndGet();
                return new ManualTask();
            });
            assertInstanceOf(CommandNotExecutedException.class, failure(rejected));
            assertEquals(0, starts.get());
        }
    }

    @Test
    void cancellingTheMonoSubscriptionRequestsCleanupWithoutReleasingTheSlot() throws Exception {
        try (Fixture f = new Fixture(properties(Duration.ofSeconds(3)))) {
            ManualTask task = new ManualTask();
            var subscription = f.resource(() -> task).execute().subscribe();
            subscription.dispose();
            assertTrue(task.cancelled.await(3, TimeUnit.SECONDS));
            assertEquals(CancellationReason.CALLER, task.reason.get());
            assertEquals(0, f.fallbacks.get(), "Downstream cancellation must not start fallback work");
            assertEquals(0, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
            assertThrows(IllegalStateException.class, f.registry::clearAllCaches);
            task.completion.complete("cleaned");
            assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
            assertDoesNotThrow(f.registry::clearAllCaches);
        }
    }

    @Test
    void cancellationInOnSubscribeDoesNotStartResourceWork() {
        try (Fixture f = new Fixture(properties(Duration.ofSeconds(3)))) {
            AtomicInteger starts = new AtomicInteger();
            f.resource(() -> {
                starts.incrementAndGet();
                return new ManualTask();
            }).execute().subscribe(new BaseSubscriber<>() {
                @Override protected void hookOnSubscribe(Subscription subscription) { cancel(); }
            });
            assertEquals(0, starts.get(), "An already-cancelled subscriber must not start I/O");
            assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
            assertDoesNotThrow(f.registry::clearAllCaches);
        }
    }

    @Test
    void retryRequiresPreviousCompletionAndUsesTheSameOccupiedPermit() throws Exception {
        CommandProperties policy = properties(Duration.ofSeconds(3)).toBuilder()
                .maxAttempts(2).replayable(true).retryWaitDuration(Duration.ZERO).build();
        try (Fixture f = new Fixture(policy)) {
            ManualTask first = new ManualTask();
            ManualTask second = new ManualTask();
            AtomicInteger starts = new AtomicInteger();
            AtomicBoolean firstCleaned = new AtomicBoolean();
            CountDownLatch secondStarted = new CountDownLatch(1);
            CommandExecution<String> execution = f.decorator.start(f.context, () -> {
                int attempt = starts.incrementAndGet();
                if (attempt == 1) return first;
                assertTrue(firstCleaned.get(), "Retry cannot start while previous resources remain alive");
                assertEquals(0, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
                secondStarted.countDown();
                return second;
            });
            assertEquals(1, starts.get());
            assertFalse(execution.result().toCompletableFuture().isDone());
            firstCleaned.set(true);
            first.completion.completeExceptionally(new RetryableException("transient"));
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, f.registry::clearAllCaches);
            second.completion.complete("recovered");
            assertEquals("recovered", execution.result().toCompletableFuture().get(3, TimeUnit.SECONDS));
            execution.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(2, starts.get());
            assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
        }
    }

    @Test
    void retryableFailureIsNotRetriedWithoutExplicitReplayabilityOptIn() throws Exception {
        try (Fixture f = new Fixture(properties(Duration.ofSeconds(3)))) {
            AtomicInteger starts = new AtomicInteger();
            ManualTask task = new ManualTask();
            CommandExecution<String> execution = f.decorator.start(f.context, () -> {
                starts.incrementAndGet();
                return task;
            });
            RetryableException expected = new RetryableException("Do not repeat a one-shot body");
            task.completion.completeExceptionally(expected);
            assertSame(expected, failure(execution));
            execution.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, starts.get());
            assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
            assertDoesNotThrow(f.registry::clearAllCaches);
        }
    }

    @Test
    void totalDeadlineExpiresDuringRetryBackoffAndNeverStartsAnotherAttempt() throws Exception {
        CommandProperties policy = properties(Duration.ofMillis(80)).toBuilder()
                .maxAttempts(2).replayable(true).retryWaitDuration(Duration.ofMillis(250)).build();
        try (Fixture f = new Fixture(policy)) {
            AtomicInteger starts = new AtomicInteger();
            CountDownLatch unexpectedRetry = new CountDownLatch(1);
            ManualTask task = new ManualTask();
            CommandExecution<String> execution = f.decorator.start(f.context, () -> {
                if (starts.incrementAndGet() > 1) unexpectedRetry.countDown();
                return task;
            });
            task.completion.completeExceptionally(new RetryableException("transient"));
            assertInstanceOf(TimeoutException.class, failure(execution));
            execution.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertFalse(unexpectedRetry.await(350, TimeUnit.MILLISECONDS), "Expired retries must be unscheduled");
            assertEquals(1, starts.get());
            assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
            assertDoesNotThrow(f.registry::clearAllCaches);
        }
    }

    private static CommandProperties properties(Duration timeout) {
        return CommandProperties.builder().timeout(timeout).maxConcurrency(1).build();
    }

    private static Throwable failure(CommandExecution<?> execution) {
        return assertThrows(ExecutionException.class,
                () -> execution.result().toCompletableFuture().get(3, TimeUnit.SECONDS)).getCause();
    }

    private static final class Fixture implements AutoCloseable {
        final CommandProperties policy;
        final CommandContext context;
        final CommandConfigRegistry registry = new CommandConfigRegistry(BulkheadRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
        final CommandResilienceDecorator decorator = new CommandResilienceDecorator(registry);
        final CommandResilienceHolder holder;
        final AtomicReference<Throwable> lastFallback = new AtomicReference<>();
        final AtomicInteger fallbacks = new AtomicInteger();

        Fixture(CommandProperties policy) {
            this.policy = policy;
            context = CommandContext.builder().commandName("safety").properties(policy).build();
            try (var lease = registry.acquire(context)) { holder = lease.holder(); }
        }

        AbstractResilienceCommand<String> simple(Supplier<Mono<String>> action) {
            return new AbstractResilienceCommand<>(decorator, policy) {
                @Override protected String getCommandName() { return "safety"; }
                @Override protected Mono<String> doExecute() { return action.get(); }
                @Override protected Mono<String> fallbackOnError(Throwable failure) { return fallback(failure); }
                @Override protected void logResilienceError(Throwable failure, String name) { }
            };
        }

        AbstractResourceCommand<String> resource(Supplier<CommandTask<String>> action) {
            return new AbstractResourceCommand<>(decorator, policy) {
                @Override protected String getCommandName() { return "safety"; }
                @Override protected CommandTask<String> doStart() { return action.get(); }
                @Override protected Mono<String> fallbackOnError(Throwable failure) { return fallback(failure); }
            };
        }

        Mono<String> fallback(Throwable failure) {
            lastFallback.set(failure);
            fallbacks.incrementAndGet();
            return Mono.just("fallback");
        }

        @Override public void close() { decorator.close(); }
    }

    private static final class ManualTask implements CommandTask<String> {
        final CompletableFuture<String> completion = new CompletableFuture<>();
        final AtomicInteger cancelCalls = new AtomicInteger();
        final AtomicReference<CancellationReason> reason = new AtomicReference<>();
        final CountDownLatch cancelled = new CountDownLatch(1);

        @Override public CompletionStage<String> completion() { return completion; }
        @Override public void cancel(CancellationReason cancellationReason) {
            reason.compareAndSet(null, cancellationReason);
            cancelCalls.incrementAndGet();
            cancelled.countDown();
        }
    }
}
