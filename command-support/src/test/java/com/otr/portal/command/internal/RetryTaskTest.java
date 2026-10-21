package com.otr.portal.command.internal;

import com.otr.portal.command.execution.CancellationReason;
import com.otr.portal.command.execution.CommandCancelledException;
import com.otr.portal.command.execution.CommandExecutor;
import com.otr.portal.command.execution.CommandTask;
import com.otr.portal.command.execution.CommandScope;
import com.otr.portal.command.exception.RetryableException;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяет последовательность повторов, доставку отмены и учёт исходов в штатных метриках Resilience4j. */
class RetryTaskTest {
    /** Повтор и последующий успех отражаются штатными событиями и счётчиками Resilience4j. */
    @Test void usesNativeRetryEventsAndSuccessMetrics() throws Exception {
        var scheduler = CommandExecutor.newScheduler("retry-test");
        var retryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var retry = retry();
            var events = new AtomicInteger();
            retry.getEventPublisher().onRetry(event -> events.incrementAndGet());
            var starts = new AtomicInteger();
            var task = RetryTask.start(() -> {
                if (starts.incrementAndGet() == 1) throw new RetryableException("retry");
                return completed("ok");
            }, retry, scheduler, retryExecutor, scope());
            assertEquals("ok", task.completion().toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(2, starts.get());
            assertEquals(1, events.get());
            assertEquals(1, retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt());
            assertEquals(0, retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt());
        } finally { scheduler.shutdownNow(); retryExecutor.shutdownNow(); }
    }

    /** Отмена во время создания повторной задачи доставляется один раз после публикации её объекта управления. */
    @Test void cancellationDuringRetryStartIsDeliveredOnceAfterTaskPublication() throws Exception {
        var scheduler = CommandExecutor.newScheduler("retry-start-race");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var retryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var starts = new AtomicInteger();
            var cleanup = new CompletableFuture<String>();
            var cancellations = new AtomicInteger();
            var cancellation = new AtomicReference<CancellationReason>();
            var cancelled = new CountDownLatch(1);
            var task = RetryTask.start(() -> {
                if (starts.incrementAndGet() == 1) throw new RetryableException("retry");
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return new CommandTask<String>() {
                    public CompletionStage<String> completion() { return cleanup; }
                    public void cancel(CancellationReason reason) {
                        cancellations.incrementAndGet();
                        cancellation.set(reason);
                        cancelled.countDown();
                    }
                };
            }, retry(), scheduler, retryExecutor, scope());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            task.cancel(CancellationReason.TIMEOUT);
            task.cancel(CancellationReason.CALLER);
            assertFalse(task.completion().toCompletableFuture().isDone());
            assertEquals(0, cancellations.get());
            release.countDown();
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            assertEquals(CancellationReason.TIMEOUT, cancellation.get());
            assertEquals(1, cancellations.get());
            assertFalse(task.completion().toCompletableFuture().isDone());
            cleanup.complete("cleaned");
            var failure = assertThrows(ExecutionException.class,
                    () -> task.completion().toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertInstanceOf(CommandCancelledException.class, failure.getCause());
            assertEquals(2, starts.get());
        } finally { release.countDown(); scheduler.shutdownNow(); retryExecutor.shutdownNow(); }
    }

    /** Ошибка Error при асинхронном повторе завершает результат и освобождает внешний слот. */
    @Test void fatalErrorInAsynchronousRetryCompletesExecutionAndReleasesPermit() throws Exception {
        var scheduler = CommandExecutor.newScheduler("retry-fatal");
        var retryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var executor = new CommandExecutor<String>("fatal", BulkheadConfig.custom()
                .maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build(),
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build(),
                CircuitBreakerConfig.ofDefaults())) {
            var starts = new AtomicInteger();
            var fatal = new OutOfMemoryError("synthetic retry failure");
            var execution = executor.executeScoped(scope -> RetryTask.start(() -> {
                if (starts.incrementAndGet() == 1) throw new RetryableException("retry");
                throw fatal;
            }, retry(), scheduler, retryExecutor, scope));
            var failure = assertThrows(ExecutionException.class,
                    () -> execution.result().toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertSame(fatal, failure.getCause());
            execution.terminated().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, executor.bulkhead().getMetrics().getAvailableConcurrentCalls());
            assertEquals(2, starts.get());
        } finally { scheduler.shutdownNow(); retryExecutor.shutdownNow(); }
    }

    /** Неповторяемая ошибка завершает первую попытку и учитывается как отказ без повтора. */
    @Test void nonRetryableFailureNeverStartsASecondAttemptAndUpdatesNativeMetrics() throws Exception {
        var scheduler = CommandExecutor.newScheduler("retry-ignore");
        var retryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var retry = retry();
            var starts = new AtomicInteger();
            var expected = new IllegalArgumentException("do not retry");
            var task = RetryTask.<String>start(() -> { starts.incrementAndGet(); throw expected; }, retry, scheduler, retryExecutor, scope());
            var failure = assertThrows(ExecutionException.class,
                    () -> task.completion().toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertSame(expected, failure.getCause());
            assertEquals(1, starts.get());
            assertEquals(1, retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt());
        } finally { scheduler.shutdownNow(); retryExecutor.shutdownNow(); }
    }

    private static CommandScope scope() {
        return new CommandScope(Duration.ofSeconds(5), ignored -> { });
    }

    private static Retry retry() {
        return Retry.of("test", RetryConfig.custom().maxAttempts(2).waitDuration(Duration.ofMillis(1))
                .retryExceptions(RetryableException.class).build());
    }
    private static CommandTask<String> completed(String value) {
        var result = CompletableFuture.completedFuture(value).minimalCompletionStage();
        return new CommandTask<>() {
            public CompletionStage<String> completion() { return result; }
            public void cancel(CancellationReason reason) { }
        };
    }
}
