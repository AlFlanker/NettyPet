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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Scheduler pauses are controlled here; application start/cancel remain nonblocking in production. */
@Timeout(10)
class DeadlineScopeRegressionTest {
    @Test void delayedTimerCannotTurnLateCleanupIntoSuccess() throws Exception {
        try (Fixture f = fixture(Duration.ofMillis(80), false)) {
            CountDownLatch release = pauseScheduler(f.scheduler());
            try {
                ManualTask task = new ManualTask();
                var execution = f.decorator.start(f.context, () -> task);
                elapseBudget();
                assertFalse(execution.result().toCompletableFuture().isDone(), "The timer is deliberately paused");
                task.completion.complete("late-success");
                assertInstanceOf(TimeoutException.class, failure(execution));
                execution.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(1, f.timeouts.get());
                assertEquals(0, f.holder.getCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls());
                assertEquals(1, f.holder.getCircuitBreaker().getMetrics().getNumberOfFailedCalls());
                assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
                assertDoesNotThrow(f.registry::clearAllCaches);
            } finally { release.countDown(); }
        }
    }

    @Test void overdueRetryCannotStartBeforeAnOverdueTimeoutWhenSchedulerResumes() throws Exception {
        try (Fixture f = fixture(Duration.ofMillis(80), true)) {
            CountDownLatch release = pauseScheduler(f.scheduler());
            try {
                AtomicInteger starts = new AtomicInteger();
                ManualTask first = new ManualTask();
                var execution = f.decorator.start(f.context, () -> {
                    if (starts.incrementAndGet() == 1) return first;
                    return completed("late-success");
                });
                first.completion.completeExceptionally(new RetryableException("transient"));
                elapseBudget();
                release.countDown();
                assertInstanceOf(TimeoutException.class, failure(execution));
                execution.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(1, starts.get());
                assertEquals(1, f.timeouts.get());
                assertEquals(1, f.holder.getCircuitBreaker().getMetrics().getNumberOfFailedCalls());
                assertEquals(0, f.holder.getCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls());
                assertDoesNotThrow(f.registry::clearAllCaches);
            } finally { release.countDown(); }
        }
    }

    @Test void timeoutDuringFirstTaskPublicationPreventsAnotherAttemptAndRetainsCleanupLease() throws Exception {
        try (Fixture f = fixture(Duration.ofMillis(80), true);
             var caller = Executors.newSingleThreadExecutor()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch timedOut = new CountDownLatch(1);
            f.holder.getTimeLimiter().getEventPublisher().onTimeout(event -> timedOut.countDown());
            AtomicInteger starts = new AtomicInteger();
            ManualTask first = new ManualTask();
            var submitted = caller.submit(() -> f.decorator.start(f.context, () -> {
                starts.incrementAndGet();
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS), "Controlled publication pause expired");
                return first;
            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(timedOut.await(3, TimeUnit.SECONDS));
                release.countDown();
                var execution = submitted.get(3, TimeUnit.SECONDS);
                assertInstanceOf(TimeoutException.class, failure(execution));
                assertTrue(first.cancelled.await(3, TimeUnit.SECONDS));
                assertEquals(CancellationReason.TIMEOUT, first.reason.get());
                assertEquals(1, first.cancels.get());
                assertEquals(0, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
                assertThrows(IllegalStateException.class, f.registry::clearAllCaches);
                first.completion.completeExceptionally(new RetryableException("failed after cleanup"));
                execution.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(1, starts.get());
                assertDoesNotThrow(f.registry::clearAllCaches);
            } finally { release.countDown(); }
        }
    }

    @Test void expiredAdmissionDoesNotStartTheInitialAttemptEvenWhenTimerWasNotArmed() throws Exception {
        try (Fixture f = fixture(Duration.ofMillis(80), false);
             var caller = Executors.newSingleThreadExecutor()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger starts = new AtomicInteger();
            f.holder.getBulkhead().getEventPublisher().onCallPermitted(event -> {
                entered.countDown();
                await(release);
            });
            var submitted = caller.submit(() -> f.decorator.start(f.context, () -> {
                starts.incrementAndGet();
                return completed("unexpected");
            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                elapseBudget();
                release.countDown();
                var execution = submitted.get(3, TimeUnit.SECONDS);
                assertInstanceOf(TimeoutException.class, failure(execution));
                execution.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(0, starts.get());
                assertEquals(1, f.timeouts.get());
                assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
                assertDoesNotThrow(f.registry::clearAllCaches);
            } finally { release.countDown(); }
        }
    }

    @Test void retryQueuedBehindBusyWorkersCannotBlockItsDeadlineOrStartAfterIt() throws Exception {
        try (Fixture f = fixture(Duration.ofMillis(80), true)) {
            var workers = f.workers();
            CountDownLatch occupied = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            for (int i = 0; i < 2; i++) workers.execute(() -> { occupied.countDown(); await(release); });
            try {
                assertTrue(occupied.await(3, TimeUnit.SECONDS));
                AtomicInteger starts = new AtomicInteger();
                var execution = f.decorator.start(f.context, () -> {
                    starts.incrementAndGet();
                    return failed(new RetryableException("queue until deadline"));
                });
                assertInstanceOf(TimeoutException.class, failure(execution));
                execution.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(1, f.timeouts.get(), "The timer remains independent of retry workers");
                assertEquals(1, starts.get());
                assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
                assertDoesNotThrow(f.registry::clearAllCaches);
                release.countDown();
                workers.submit(() -> { }).get(3, TimeUnit.SECONDS);
                assertEquals(1, starts.get(), "A queued retry must recheck its scope on worker pickup");
            } finally { release.countDown(); }
        }
    }

    @Test void retryWorkerSaturationIsLocalAndShutdownReleasesQueuedRetryLeases() throws Exception {
        var policy = policy(Duration.ofSeconds(3), true).toBuilder().maxConcurrency(2).build();
        try (Fixture f = new Fixture(policy)) {
            var oldWorkers = f.workers();
            oldWorkers.shutdownNow();
            var workers = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
            field("retryExecutor").set(f.decorator, workers);
            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            workers.execute(() -> { occupied.countDown(); await(release); });
            try {
                assertTrue(occupied.await(3, TimeUnit.SECONDS));
                AtomicInteger starts = new AtomicInteger();
                var queued = f.decorator.start(f.context, () -> {
                    starts.incrementAndGet();
                    return failed(new RetryableException("queue retry"));
                });
                // Runs after the zero-delay dispatch, proving the single worker queue is occupied.
                f.scheduler().schedule(() -> { }, 30, TimeUnit.MILLISECONDS).get(3, TimeUnit.SECONDS);
                assertEquals(1, workers.getQueue().size());
                var rejected = f.decorator.start(f.context, () -> {
                    starts.incrementAndGet();
                    return failed(new RetryableException("reject retry"));
                });
                assertInstanceOf(CommandNotExecutedException.class, failure(rejected));
                rejected.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(1, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
                f.decorator.close();
                assertInstanceOf(CommandCancelledException.class, failure(queued));
                queued.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(2, starts.get(), "Neither queued nor rejected retry may start application work");
                assertEquals(2, f.holder.getBulkhead().getMetrics().getAvailableConcurrentCalls());
                assertEquals(0, f.holder.getCircuitBreaker().getMetrics().getNumberOfBufferedCalls());
                assertDoesNotThrow(f.registry::clearAllCaches);
            } finally { release.countDown(); workers.shutdownNow(); }
        }
    }

    private static Fixture fixture(Duration timeout, boolean retry) { return new Fixture(policy(timeout, retry)); }
    private static CommandProperties policy(Duration timeout, boolean retry) {
        return CommandProperties.builder().timeout(timeout).maxConcurrency(1).maxAttempts(retry ? 2 : 1)
                .replayable(retry).retryWaitDuration(Duration.ofMillis(10)).build();
    }
    private static Throwable failure(CommandExecution<?> execution) {
        return assertThrows(ExecutionException.class,
                () -> execution.result().toCompletableFuture().get(3, TimeUnit.SECONDS)).getCause();
    }
    private static void elapseBudget() throws InterruptedException {
        assertFalse(new CountDownLatch(1).await(180, TimeUnit.MILLISECONDS));
    }
    private static CountDownLatch pauseScheduler(ScheduledThreadPoolExecutor scheduler) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        scheduler.execute(() -> { entered.countDown(); await(release); });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        return release;
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private static Field field(String name) throws Exception {
        Field field = CommandResilienceDecorator.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static CommandTask<String> completed(String value) {
        return fromStage(CompletableFuture.completedFuture(value));
    }
    private static CommandTask<String> failed(Throwable failure) {
        return fromStage(CompletableFuture.failedFuture(failure));
    }
    private static CommandTask<String> fromStage(CompletionStage<String> result) {
        return new CommandTask<>() {
            public CompletionStage<String> completion() { return result; }
            public void cancel(CancellationReason reason) { }
        };
    }
    private static final class ManualTask implements CommandTask<String> {
        final CompletableFuture<String> completion = new CompletableFuture<>();
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicReference<CancellationReason> reason = new AtomicReference<>();
        final CountDownLatch cancelled = new CountDownLatch(1);
        public CompletionStage<String> completion() { return completion; }
        public void cancel(CancellationReason reason) {
            this.reason.set(reason); cancels.incrementAndGet(); cancelled.countDown();
        }
    }
    private static final class Fixture implements AutoCloseable {
        final CommandConfigRegistry registry = new CommandConfigRegistry(BulkheadRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
        final CommandResilienceDecorator decorator = new CommandResilienceDecorator(registry);
        final CommandContext context;
        final CommandResilienceHolder holder;
        final AtomicInteger timeouts = new AtomicInteger();
        Fixture(CommandProperties properties) {
            context = CommandContext.builder().commandName("deadline").properties(properties).build();
            try (var lease = registry.acquire(context)) { holder = lease.holder(); }
            holder.getTimeLimiter().getEventPublisher().onTimeout(event -> timeouts.incrementAndGet());
        }
        ScheduledThreadPoolExecutor scheduler() throws Exception { return (ScheduledThreadPoolExecutor) field("scheduler").get(decorator); }
        ThreadPoolExecutor workers() throws Exception { return (ThreadPoolExecutor) field("retryExecutor").get(decorator); }
        public void close() { decorator.close(); }
    }
}
