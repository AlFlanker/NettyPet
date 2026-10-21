package com.otr.portal.command.execution;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandExecutorTest {
    @Test
    void successReleasesTheSlotOnlyAfterTaskCompletion() throws Exception {
        try (var executor = executor(1, Duration.ofMinutes(1))) {
            var task = new ManualTask();
            var execution = executor.execute(() -> task);
            assertEquals(0, available(executor));
            assertFalse(execution.result().toCompletableFuture().isDone());
            assertFalse(execution.terminated().toCompletableFuture().isDone());
            task.completion.complete("done");
            assertEquals("done", await(execution.result()));
            await(execution.terminated());
            assertEquals(1, available(executor));
            assertEquals(0, executor.scheduledTimeoutCount());
        }
    }

    @Test
    void openCircuitNeverStartsTheCommandAndReturnsBulkheadPermission() throws Exception {
        try (var executor = executor(1, Duration.ofMinutes(1))) {
            executor.circuitBreaker().transitionToOpenState();
            var starts = new AtomicInteger();
            var execution = executor.execute(() -> {
                starts.incrementAndGet();
                return new ManualTask();
            });
            assertInstanceOf(CallNotPermittedException.class, failure(execution));
            await(execution.terminated());
            assertEquals(0, starts.get());
            assertEquals(1, available(executor));
            assertEquals(0, executor.scheduledTimeoutCount());
        }
    }

    @Test
    void overloadedBulkheadRejectsWithoutStartingOrWaiting() throws Exception {
        try (var executor = executor(1, Duration.ofMinutes(1))) {
            var task = new ManualTask();
            var first = executor.execute(() -> task);
            var rejected = executor.execute(() -> fail("Команда не должна запускаться"));
            assertInstanceOf(BulkheadFullException.class, failure(rejected));
            await(rejected.terminated());
            assertEquals(0, available(executor));
            task.completion.complete("done");
            await(first.terminated());
            assertEquals(1, available(executor));
        }
    }

    @Test
    void timeoutCancelsTaskButKeepsSlotUntilActualCleanup() throws Exception {
        try (var executor = executor(1, Duration.ofMillis(40))) {
            var task = new ManualTask();
            var finished = new AtomicInteger();
            executor.bulkhead().getEventPublisher().onCallFinished(event -> finished.incrementAndGet());
            var execution = executor.execute(() -> task);
            assertInstanceOf(TimeoutException.class, failure(execution));
            assertTrue(task.cancelled.await(3, TimeUnit.SECONDS));
            assertEquals(CancellationReason.TIMEOUT, task.reason.get());
            assertFalse(task.completion.isDone(), "TimeLimiter не должен менять future задачи");
            assertFalse(execution.terminated().toCompletableFuture().isDone());
            assertEquals(0, available(executor));
            assertEquals(0, finished.get());
            var rejected = executor.execute(() -> fail("Очистка ещё держит слот"));
            assertInstanceOf(BulkheadFullException.class, failure(rejected));
            task.completion.complete("late success");
            await(execution.terminated());
            assertEquals(1, available(executor));
            assertEquals(1, task.cancelCalls.get());
            assertEquals(1, finished.get());
            assertInstanceOf(TimeoutException.class, failure(execution));
            assertEquals(0, executor.scheduledTimeoutCount());
        }
    }

    @Test
    void timeoutIsArmedBeforeStartAndCancellationIsHandedToPublishedTask() throws Exception {
        try (var executor = executor(1, Duration.ofMillis(40));
             var worker = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var releaseStart = new CountDownLatch(1);
            var timeout = new CountDownLatch(1);
            executor.timeLimiter().getEventPublisher().onTimeout(event -> timeout.countDown());
            var task = new ManualTask();
            var submitted = worker.submit(() -> executor.execute(() -> {
                entered.countDown();
                // Управляемая задержка проверяет гонку; настоящий start() не блокируется.
                if (!releaseStart.await(3, TimeUnit.SECONDS)) throw new AssertionError("start latch expired");
                return task;
            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(timeout.await(3, TimeUnit.SECONDS));
                assertEquals(0, task.cancelCalls.get());
            } finally {
                releaseStart.countDown();
            }
            var execution = submitted.get(3, TimeUnit.SECONDS);
            assertTrue(task.cancelled.await(3, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure(execution));
            assertEquals(CancellationReason.TIMEOUT, task.reason.get());
            assertEquals(1, task.cancelCalls.get());
            assertEquals(0, available(executor));
            task.completion.complete("cleanup finished");
            await(execution.terminated());
            assertEquals(1, available(executor));
        }
    }

    @Test
    void closeDuringStartHandsShutdownToTaskExactlyOnce() throws Exception {
        try (var executor = executor(1, Duration.ofMinutes(1))) {
            var task = new ManualTask();
            var execution = executor.execute(() -> {
                executor.close();
                return task;
            });
            assertInstanceOf(CommandCancelledException.class, failure(execution));
            assertEquals(CancellationReason.SHUTDOWN, task.reason.get());
            assertEquals(1, task.cancelCalls.get());
            execution.cancel();
            executor.close();
            assertEquals(1, task.cancelCalls.get());
            assertEquals(0, available(executor));
            task.completion.complete("cleanup finished");
            await(execution.terminated());
            assertEquals(1, available(executor));
        }
    }

    @Test
    void closeFromAdmissionEventPreventsStartAndDoesNotLeakPermission() throws Exception {
        try (var executor = executor(1, Duration.ofMinutes(1))) {
            executor.bulkhead().getEventPublisher().onCallPermitted(event -> executor.close());
            var execution = executor.execute(() -> fail("Executor уже закрыт"));
            assertInstanceOf(CommandNotExecutedException.class, failure(execution));
            await(execution.terminated());
            assertEquals(1, available(executor));
        }
    }

    @Test
    void closeBetweenDeadlineCheckAndSchedulingRemainsAnIgnoredCancellation() throws Exception {
        try (var executor = executor(1, Duration.ofMinutes(1))) {
            rejectScheduling(executor, true);
            var execution = executor.execute(() -> fail("Закрытый executor не запускает команду"));
            assertInstanceOf(CommandCancelledException.class, failure(execution));
            await(execution.terminated());
            assertEquals(1, available(executor));
            assertEquals(0, executor.circuitBreaker().getMetrics().getNumberOfBufferedCalls());
            assertEquals(0, executor.scheduledTimeoutCount());
        }
    }

    @Test
    void unavailableSchedulerIsALocalRejectionRatherThanUpstreamFailure() throws Exception {
        try (var executor = executor(1, Duration.ofMinutes(1))) {
            rejectScheduling(executor, false);
            var execution = executor.execute(() -> fail("Без deadline команда не запускается"));
            assertInstanceOf(CommandNotExecutedException.class, failure(execution));
            await(execution.terminated());
            assertEquals(1, available(executor));
            assertEquals(0, executor.circuitBreaker().getMetrics().getNumberOfBufferedCalls());
        }
    }

    @Test
    void callerCancellationIsIdempotentAndDoesNotReleaseBeforeCleanup() throws Exception {
        try (var executor = executor(1, Duration.ofMinutes(1))) {
            var task = new ManualTask();
            var execution = executor.execute(() -> task);
            execution.cancel();
            execution.cancel();
            executor.close();
            assertInstanceOf(CommandCancelledException.class, failure(execution));
            assertEquals(CancellationReason.CALLER, task.reason.get());
            assertEquals(1, task.cancelCalls.get());
            assertEquals(0, available(executor));
            assertFalse(execution.terminated().toCompletableFuture().isDone());
            task.completion.completeExceptionally(new CommandCancelledException("Cleaned up"));
            task.completion.complete("duplicate completion");
            await(execution.terminated());
            assertEquals(1, available(executor));
            assertEquals(0, executor.circuitBreaker().getMetrics().getNumberOfBufferedCalls());
        }
    }

    @Test
    void returnedFutureCopiesCannotCancelTheExecution() throws Exception {
        try (var executor = executor(1, Duration.ofMinutes(1))) {
            var task = new ManualTask();
            var execution = executor.execute(() -> task);
            assertTrue(execution.result().toCompletableFuture().cancel(true));
            assertTrue(execution.terminated().toCompletableFuture().cancel(true));
            assertFalse(task.completion.isDone());
            assertEquals(0, task.cancelCalls.get());
            task.completion.complete("unaffected");
            assertEquals("unaffected", await(execution.result()));
            await(execution.terminated());
            assertEquals(1, available(executor));
        }
    }

    @Test
    void exceptionalCompletionRemovesLongDeadlineFromSchedulerQueue() throws Exception {
        try (var executor = executor(1, Duration.ofHours(1))) {
            executor.circuitBreaker().transitionToDisabledState();
            for (int i = 0; i < 100; i++) {
                var task = new ManualTask();
                var execution = executor.execute(() -> task);
                var expected = new IllegalStateException("upstream failed");
                task.completion.completeExceptionally(expected);
                assertSame(expected, failure(execution));
                await(execution.terminated());
            }
            assertEquals(0, executor.scheduledTimeoutCount());
            assertEquals(1, available(executor));
        }
    }

    @Test
    void startExceptionReleasesSlotAndCancelsItsTimer() throws Exception {
        try (var executor = executor(1, Duration.ofHours(1))) {
            var expected = new IllegalStateException("start failed");
            var execution = executor.execute(() -> { throw expected; });
            assertSame(expected, failure(execution));
            await(execution.terminated());
            assertEquals(1, available(executor));
            assertEquals(0, executor.scheduledTimeoutCount());
        }
    }

    @Test
    void startErrorIsRethrownAfterPermitAndTimerCleanup() {
        try (var executor = executor(1, Duration.ofHours(1))) {
            var fatal = new OutOfMemoryError("start failed");
            assertSame(fatal, assertThrows(OutOfMemoryError.class,
                    () -> executor.execute(() -> { throw fatal; })));
            assertEquals(1, available(executor));
            assertEquals(0, executor.scheduledTimeoutCount());
        }
    }

    @Test
    void wrappedLocalFailuresAreIgnoredRatherThanRecordedAsSuccesses() throws Exception {
        try (var executor = executor(1, Duration.ofMinutes(1))) {
            for (var expected : new Exception[]{
                    new CommandCancelledException("client cancelled"),
                    new CommandNotExecutedException("local rejection"),
                    new TimeoutException("end-to-end deadline")}) {
                var task = new ManualTask();
                var execution = executor.execute(() -> task);
                task.completion.completeExceptionally(new CompletionException(new ExecutionException(expected)));
                assertSame(expected, failure(execution));
                await(execution.terminated());
            }
            assertEquals(0, executor.circuitBreaker().getMetrics().getNumberOfBufferedCalls());
            assertEquals(0, executor.circuitBreaker().getMetrics().getNumberOfSuccessfulCalls());
            assertEquals(0, executor.circuitBreaker().getMetrics().getNumberOfFailedCalls());
            assertEquals(1, available(executor));
        }
    }

    @Test
    void ignoredHalfOpenCancellationAndTimeoutReturnProbePermissionWithoutSamples() throws Exception {
        for (boolean callerCancellation : new boolean[]{true, false}) {
            try (var executor = new CommandExecutor<String>("half-open", BulkheadConfig.custom()
                    .maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build(),
                    TimeLimiterConfig.custom().timeoutDuration(Duration.ofMinutes(1)).build(),
                    CircuitBreakerConfig.custom().slidingWindowSize(2).minimumNumberOfCalls(2)
                            .permittedNumberOfCallsInHalfOpenState(1)
                            .ignoreExceptions(CommandCancelledException.class, TimeoutException.class).build())) {
                var breaker = executor.circuitBreaker();
                breaker.transitionToOpenState();
                breaker.transitionToHalfOpenState();
                var task = new ManualTask();
                var ignored = executor.execute(() -> task);
                if (callerCancellation) {
                    ignored.cancel();
                    assertInstanceOf(CommandCancelledException.class, failure(ignored));
                    assertEquals(0, available(executor));
                    task.completion.completeExceptionally(new CommandCancelledException("Cleanup finished"));
                } else {
                    task.completion.completeExceptionally(new TimeoutException("End-to-end deadline"));
                    assertInstanceOf(TimeoutException.class, failure(ignored));
                }
                await(ignored.terminated());
                assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());
                assertEquals(0, breaker.getMetrics().getNumberOfBufferedCalls());
                assertEquals(0, breaker.getMetrics().getNumberOfSuccessfulCalls());
                assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
                assertEquals(1, available(executor));

                // Единственный probe-slot должен вернуться после ignore, иначе следующий
                // запуск получит CallNotPermittedException и HALF_OPEN останется навсегда.
                var recoveredTask = new ManualTask();
                recoveredTask.completion.complete("recovered");
                var recovered = executor.execute(() -> recoveredTask);
                assertEquals("recovered", await(recovered.result()));
                await(recovered.terminated());
                assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
                assertEquals(1, available(executor));
            }
        }
    }

    @Test
    void closeRequestsCleanupForEveryActiveTaskAndRejectsNewCommands() throws Exception {
        try (var executor = executor(2, Duration.ofMinutes(1))) {
            var first = new ManualTask();
            var second = new ManualTask();
            var firstExecution = executor.execute(() -> first);
            var secondExecution = executor.execute(() -> second);
            executor.close();
            assertEquals(CancellationReason.SHUTDOWN, first.reason.get());
            assertEquals(CancellationReason.SHUTDOWN, second.reason.get());
            assertInstanceOf(CommandCancelledException.class, failure(firstExecution));
            assertInstanceOf(CommandCancelledException.class, failure(secondExecution));
            assertEquals(0, available(executor));
            assertEquals(0, executor.scheduledTimeoutCount());
            var rejected = executor.execute(() -> fail("Executor закрыт"));
            assertInstanceOf(CommandNotExecutedException.class, failure(rejected));
            first.completion.complete("first cleaned");
            second.completion.complete("second cleaned");
            await(firstExecution.terminated());
            await(secondExecution.terminated());
            assertEquals(2, available(executor));
        }
    }

    @Test
    void waitingBulkheadIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new CommandExecutor<>("blocking",
                BulkheadConfig.custom().maxWaitDuration(Duration.ofMillis(1)).build(),
                TimeLimiterConfig.ofDefaults(), CircuitBreakerConfig.ofDefaults()));
    }

    @Test
    void overflowingDeadlineIsRejectedBeforeCreatingExecutionResources() {
        var timeout = TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(Long.MAX_VALUE)).build();
        assertThrows(IllegalArgumentException.class, () -> new CommandExecutor<>("overflow",
                BulkheadConfig.ofDefaults(), timeout, CircuitBreakerConfig.ofDefaults()));
        var scheduler = CommandExecutor.newScheduler("invalid-budget-test");
        try {
            assertThrows(IllegalArgumentException.class, () -> new CommandExecutor<>(
                    io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("overflow"),
                    io.github.resilience4j.timelimiter.TimeLimiter.of("overflow", timeout),
                    CircuitBreaker.ofDefaults("overflow"), scheduler));
            assertEquals(0, scheduler.getQueue().size());
        } finally { scheduler.shutdownNow(); }
    }

    private static CommandExecutor<String> executor(int permits, Duration timeout) {
        return new CommandExecutor<>("test", BulkheadConfig.custom()
                .maxConcurrentCalls(permits).maxWaitDuration(Duration.ZERO).build(),
                TimeLimiterConfig.custom().timeoutDuration(timeout).cancelRunningFuture(true).build(),
                CircuitBreakerConfig.custom().slidingWindowSize(2).minimumNumberOfCalls(2)
                        .ignoreExceptions(CommandCancelledException.class, CommandNotExecutedException.class,
                                TimeoutException.class).build());
    }

    private static void rejectScheduling(CommandExecutor<?> executor, boolean closeFirst) throws Exception {
        // Подменяем только в тесте: production API не содержит точки остановки scheduler.
        var field = CommandExecutor.class.getDeclaredField("scheduler");
        field.setAccessible(true);
        var original = (ScheduledThreadPoolExecutor) field.get(executor);
        var scheduler = mock(ScheduledThreadPoolExecutor.class, AdditionalAnswers.delegatesTo(original));
        doAnswer(invocation -> {
            if (closeFirst) executor.close();
            throw new RejectedExecutionException("Deterministic scheduling race");
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        field.set(executor, scheduler);
    }

    private static int available(CommandExecutor<?> executor) {
        return executor.bulkhead().getMetrics().getAvailableConcurrentCalls();
    }

    private static Throwable failure(CommandExecution<?> execution) {
        return assertThrows(ExecutionException.class,
                () -> execution.result().toCompletableFuture().get(3, TimeUnit.SECONDS)).getCause();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(3, TimeUnit.SECONDS);
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
