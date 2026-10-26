package dev.example.integrationhost;

import com.otr.portal.proxy.caching.CachePublicationQueue;
import com.otr.portal.proxy.caching.CachedResponse;
import com.otr.portal.proxy.caching.ContentCachingResponse;
import com.otr.portal.proxy.caching.SharedResponseCache;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mock.web.MockHttpServletResponse;

import static com.otr.portal.proxy.caching.CachePublicationQueue.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same queue used by the portal adapter; latches reproduce saturation/shutdown without sleeps. */
@Timeout(15)
class CachePublicationQueueTest {
    private static final int LIMIT = 4096;
    private static final long RESERVATION = LIMIT * 2L;
    private static final byte[] BODY = {1, 3, 5, 7, 9};

    @Test
    void completedSnapshotIsPublishedOffTheCallerThreadAndOnlyAfterWorkerCleanup() throws Exception {
        var cache = new SharedResponseCache();
        var budget = budget();
        var capture = gated(budget);
        try (var queue = new CachePublicationQueue(cache, 1, 1)) {
            Thread caller = Thread.currentThread();
            CompletionStage<CachePublicationQueue.Result> result = queue.publish("first", capture, true);
            assertTrue(capture.entered.await(3, TimeUnit.SECONDS));
            assertNotSame(caller, capture.worker.get());
            assertFalse(result.toCompletableFuture().isDone());
            assertNull(cache.get("first"));
            assertEquals(1, queue.activeTasks());
            assertEquals(RESERVATION, budget.reservedBytes());
            capture.release.countDown();
            assertEquals(STORED, result.toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertArrayEquals(BODY, cache.get("first").getContent());
            assertEquals(0, budget.reservedBytes());
            assertEquals(0, queue.activeTasks());
            assertEquals(0, queue.pendingTasks());
        } finally { capture.release.countDown(); }
    }

    @Test
    void fullQueueRejectsOnlyTheNewCaptureAndPreservesAlreadyDeliveredBodies() throws Exception {
        var cache = new SharedResponseCache();
        var budget = budget();
        var active = gated(budget);
        var queuedResponse = new MockHttpServletResponse();
        var queued = capture(queuedResponse, budget);
        var rejectedResponse = new MockHttpServletResponse();
        var rejected = capture(rejectedResponse, budget);
        try (var queue = new CachePublicationQueue(cache, 1, 1)) {
            var first = queue.publish("active", active, true);
            assertTrue(active.entered.await(3, TimeUnit.SECONDS));
            var second = queue.publish("queued", queued, true);
            assertEquals(1, queue.queuedTasks());
            assertEquals(2, queue.pendingTasks());
            assertEquals(REJECTED, queue.publish("rejected", rejected, true).toCompletableFuture().join());
            assertEquals(0, rejected.getContentSize());
            assertArrayEquals(BODY, rejectedResponse.getContentAsByteArray(), "Queue saturation cannot change the completed HTTP body");
            assertEquals(2 * RESERVATION, budget.reservedBytes());
            active.release.countDown();
            assertEquals(STORED, first.toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(STORED, second.toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertArrayEquals(BODY, cache.get("queued").getContent());
            assertNull(cache.get("rejected"));
            assertEquals(0, budget.reservedBytes());
            assertEquals(0, queue.pendingTasks());
        } finally { active.release.countDown(); }
    }

    @Test
    void shutdownDiscardsQueuedCapturesImmediatelyAndPreventsLateActivePublication() throws Exception {
        var cache = new SharedResponseCache();
        var budget = budget();
        var active = gated(budget);
        var queue = new CachePublicationQueue(cache, 1, 2);
        try {
            var first = queue.publish("active", active, true);
            assertTrue(active.entered.await(3, TimeUnit.SECONDS));
            var second = queue.publish("queued-one", capture(budget), true);
            var third = queue.publish("queued-two", capture(budget), true);
            assertEquals(3 * RESERVATION, budget.reservedBytes());
            CompletableFuture.runAsync(queue::close).get(3, TimeUnit.SECONDS);
            assertTrue(queue.isClosed());
            assertEquals(CLOSED, second.toCompletableFuture().join());
            assertEquals(CLOSED, third.toCompletableFuture().join());
            assertFalse(first.toCompletableFuture().isDone(), "A running worker still owns its capture");
            assertEquals(RESERVATION, budget.reservedBytes());
            assertEquals(0, queue.queuedTasks());
            assertEquals(1, queue.pendingTasks());
            assertFalse(queue.awaitTermination(Duration.ZERO));
            active.release.countDown();
            assertEquals(CLOSED, first.toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertTrue(queue.awaitTermination(Duration.ofSeconds(3)));
            assertEquals(0, cache.size());
            assertEquals(0, budget.reservedBytes());
            assertEquals(0, queue.pendingTasks());
        } finally { active.release.countDown(); queue.close(); }
    }

    @Test
    void repeatedCloseAndSubmissionAfterCloseCannotRetainCapture() throws Exception {
        var budget = budget();
        var cache = new SharedResponseCache();
        var queue = new CachePublicationQueue(cache);
        queue.close();
        queue.close();
        ContentCachingResponse capture = capture(budget);
        assertEquals(CLOSED, queue.publish("late", capture, true).toCompletableFuture().join());
        assertEquals(0, capture.getContentSize());
        assertEquals(0, budget.reservedBytes());
        assertEquals(0, queue.pendingTasks());
        assertEquals(0, cache.size());
        assertTrue(queue.awaitTermination(Duration.ofSeconds(3)));
    }

    @Test
    void shutdownContinuesDiscardingAllQueuedCapturesAfterACleanupFailure() throws Exception {
        var budget = budget();
        var cache = new SharedResponseCache();
        var active = gated(budget);
        var fatal = new FatalProbe();
        var broken = new ContentCachingResponse(new MockHttpServletResponse(), LIMIT, budget) {
            @Override public synchronized void discardCapture() {
                super.discardCapture();
                throw fatal;
            }
        };
        fill(broken);
        var queue = new CachePublicationQueue(cache, 1, 2);
        try {
            var running = queue.publish("active", active, true);
            assertTrue(active.entered.await(3, TimeUnit.SECONDS));
            var failedCleanup = queue.publish("broken", broken, true);
            var last = queue.publish("last", capture(budget), true);
            assertSame(fatal, assertThrows(FatalProbe.class, queue::close));
            assertSame(fatal, assertThrows(CompletionException.class,
                    () -> failedCleanup.toCompletableFuture().join()).getCause());
            assertEquals(CLOSED, last.toCompletableFuture().join());
            assertEquals(RESERVATION, budget.reservedBytes());
            assertEquals(1, queue.pendingTasks());
            active.release.countDown();
            assertEquals(CLOSED, running.toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertTrue(queue.awaitTermination(Duration.ofSeconds(3)));
            assertEquals(0, budget.reservedBytes());
            assertEquals(0, queue.pendingTasks());
            assertEquals(0, cache.size());
        } finally { active.release.countDown(); queue.close(); }
    }

    @Test
    void failedHttpExchangeBypassesTheWorkerAndImmediatelyReleasesItsReservation() throws Exception {
        var budget = budget();
        var response = new MockHttpServletResponse();
        var capture = new ContentCachingResponse(response, LIMIT, budget) {
            @Override public Optional<CachedResponse> finishCapture(boolean successful) {
                fail("An unsuccessful exchange must never be snapshotted");
                return Optional.empty();
            }
        };
        fill(capture);
        try (var queue = new CachePublicationQueue(new SharedResponseCache())) {
            assertEquals(BYPASSED, queue.publish("failed", capture, false).toCompletableFuture().join());
            assertEquals(0, queue.activeTasks());
            assertEquals(0, queue.queuedTasks());
            assertEquals(0, budget.reservedBytes());
            assertArrayEquals(BODY, response.getContentAsByteArray());
        }
    }

    @Test
    void snapshotFailureCompletesExceptionallyAfterCleanupAndWorkerAcceptsTheNextEntry() throws Exception {
        var budget = budget();
        var failure = new IllegalStateException("snapshot failure");
        var capture = new ContentCachingResponse(new MockHttpServletResponse(), LIMIT, budget) {
            @Override public Optional<CachedResponse> finishCapture(boolean successful) { throw failure; }
        };
        fill(capture);
        var cache = new SharedResponseCache();
        try (var queue = new CachePublicationQueue(cache)) {
            CompletionException actual = assertThrows(CompletionException.class,
                    () -> queue.publish("failed", capture, true).toCompletableFuture().join());
            assertSame(failure, actual.getCause());
            assertEquals(0, budget.reservedBytes());
            assertEquals(0, queue.pendingTasks());
            assertNull(cache.get("failed"));
            assertEquals(STORED, queue.publish("next", capture(budget), true).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(0, budget.reservedBytes());
        }
    }

    @Test
    void uncacheableCompleteResponseIsBypassedWithoutRetainingCapture() throws Exception {
        var budget = budget();
        var capture = capture(budget);
        capture.setHeader("Cache-Control", "no-store");
        var cache = new SharedResponseCache();
        try (var queue = new CachePublicationQueue(cache)) {
            assertEquals(BYPASSED, queue.publish("private", capture, true).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(0, cache.size());
            assertEquals(0, budget.reservedBytes());
            assertEquals(0, queue.pendingTasks());
        }
    }

    @Test
    void observerFutureCannotCancelOrForgeTheActualPublicationResult() throws Exception {
        var budget = budget();
        var capture = gated(budget);
        var cache = new SharedResponseCache();
        try (var queue = new CachePublicationQueue(cache)) {
            var stage = queue.publish("entry", capture, true);
            assertTrue(capture.entered.await(3, TimeUnit.SECONDS));
            assertTrue(stage.toCompletableFuture().cancel(false));
            assertTrue(stage.toCompletableFuture().complete(REJECTED));
            assertFalse(stage.toCompletableFuture().isDone());
            capture.release.countDown();
            assertEquals(STORED, stage.toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertNotNull(cache.get("entry"));
            assertEquals(0, budget.reservedBytes());
        } finally { capture.release.countDown(); }
    }

    @Test
    void racingSubmitAndCloseReleasesEveryQueuedOrRejectedCapture() throws Exception {
        for (int iteration = 0; iteration < 12; iteration++) {
            var budget = budget();
            var cache = new SharedResponseCache();
            var active = gated(budget);
            var queue = new CachePublicationQueue(cache, 1, 4);
            var clients = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var first = queue.publish("active", active, true);
                assertTrue(active.entered.await(3, TimeUnit.SECONDS));
                var start = new CyclicBarrier(10);
                List<CompletableFuture<CachePublicationQueue.Result>> outcomes = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    String key = "racing-" + i;
                    var candidate = capture(budget);
                    outcomes.add(CompletableFuture.supplyAsync(() -> {
                        barrier(start);
                        return queue.publish(key, candidate, true).toCompletableFuture().join();
                    }, clients));
                }
                var close = CompletableFuture.runAsync(() -> { barrier(start); queue.close(); }, clients);
                barrier(start);
                close.get(3, TimeUnit.SECONDS);
                for (var result : outcomes) assertTrue(List.of(CLOSED, REJECTED).contains(result.get(3, TimeUnit.SECONDS)));
                assertEquals(RESERVATION, budget.reservedBytes());
                assertEquals(1, queue.pendingTasks());
                active.release.countDown();
                assertEquals(CLOSED, first.toCompletableFuture().get(3, TimeUnit.SECONDS));
                assertTrue(queue.awaitTermination(Duration.ofSeconds(3)));
                assertEquals(0, budget.reservedBytes());
                assertEquals(0, queue.pendingTasks());
                assertEquals(0, cache.size());
            } finally { active.release.countDown(); queue.close(); clients.close(); }
        }
    }

    private static void barrier(CyclicBarrier barrier) {
        try { barrier.await(3, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new CompletionException(failure); }
    }

    private static final class FatalProbe extends VirtualMachineError { }

    private static ContentCachingResponse.CaptureBudget budget() {
        return new ContentCachingResponse.CaptureBudget(1024 * 1024);
    }

    private static ContentCachingResponse capture(ContentCachingResponse.CaptureBudget budget) throws IOException {
        return capture(new MockHttpServletResponse(), budget);
    }

    private static ContentCachingResponse capture(MockHttpServletResponse response,
            ContentCachingResponse.CaptureBudget budget) throws IOException {
        var capture = new ContentCachingResponse(response, LIMIT, budget);
        fill(capture);
        return capture;
    }

    private static GatedCapture gated(ContentCachingResponse.CaptureBudget budget) throws IOException {
        var capture = new GatedCapture(budget);
        fill(capture);
        return capture;
    }

    private static void fill(ContentCachingResponse capture) throws IOException {
        capture.setHeader("Cache-Control", "public, max-age=600");
        capture.setContentLength(BODY.length);
        capture.getOutputStream().write(BODY);
    }

    private static final class GatedCapture extends ContentCachingResponse {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> worker = new AtomicReference<>();

        GatedCapture(CaptureBudget budget) { super(new MockHttpServletResponse(), LIMIT, budget); }

        @Override public Optional<CachedResponse> finishCapture(boolean successful) {
            worker.set(Thread.currentThread());
            entered.countDown();
            // Deliberately defer a running snapshot even across shutdown interruption.
            // Its reservation must not be released until the worker really returns.
            boolean interrupted = false;
            try {
                while (true) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                return super.finishCapture(successful);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
}
