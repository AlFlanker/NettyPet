package com.otr.portal.proxy.caching;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, application-owned publication of completed captures off transport callbacks.
 * A full or closed queue discards capture, never changes the already completed HTTP response.
 * No task reads Servlet state. Close discards queued work and prevents later store publication;
 * an already running snapshot retains its reservation until that worker returns.
 */
public final class CachePublicationQueue implements AutoCloseable {
    public enum Result { STORED, BYPASSED, REJECTED, CLOSED }

    private final SharedResponseCache cache;
    private final ThreadPoolExecutor executor;
    private final Object lifecycle = new Object();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean closed;

    public CachePublicationQueue(SharedResponseCache cache) { this(cache, 1, 4); }

    public CachePublicationQueue(SharedResponseCache cache, int workers, int maxQueued) {
        this.cache = Objects.requireNonNull(cache, "cache");
        if (workers < 1 || maxQueued < 1)
            throw new IllegalArgumentException("workers and maxQueued must be positive");
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxQueued),
                Thread.ofPlatform().name("ProxyCachePublication-", 0).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Call once for a capture, after the exchange has fully completed. A false outcome discards
     * it immediately. The returned stage completes only after the task's capture cleanup;
     * observers must not perform blocking work on the publication worker.
     */
    public CompletionStage<Result> publish(String key, ContentCachingResponse capture, boolean successful) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(capture, "capture");
        if (!successful) {
            capture.discardCapture();
            return CompletableFuture.completedStage(Result.BYPASSED);
        }
        Job job = new Job(key, capture);
        Result refused = null;
        synchronized (lifecycle) {
            if (closed) refused = Result.CLOSED;
            else {
                pending.incrementAndGet();
                try { executor.execute(job); }
                catch (RejectedExecutionException full) {
                    pending.decrementAndGet();
                    refused = Result.REJECTED;
                } catch (RuntimeException | Error failure) {
                    pending.decrementAndGet();
                    Throwable primary = failure;
                    try { capture.discardCapture(); }
                    catch (RuntimeException | Error cleanup) { primary = combine(primary, cleanup); }
                    rethrow(primary);
                }
            }
        }
        if (refused != null) {
            capture.discardCapture();
            job.result.complete(refused);
        }
        return job.result.minimalCompletionStage();
    }

    public int queuedTasks() { return executor.getQueue().size(); }
    public int activeTasks() { return active.get(); }
    /** Accepted tasks, including those queued or currently releasing capture state. */
    public int pendingTasks() { return pending.get(); }
    public boolean isClosed() { return closed; }

    /** Close never waits for an application override of finishCapture() to return. */
    @Override public void close() {
        List<Runnable> queued;
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            // The same lock protects store.put(), so no store mutation can start after close.
            queued = executor.shutdownNow();
        }
        Throwable failure = null;
        for (Runnable task : queued) {
            try { ((Job) task).discardClosed(); }
            catch (RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
        }
        if (failure != null) rethrow(failure);
    }

    /** Optional bounded shutdown wait; callers must close first. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must be nonnegative");
        return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private final class Job implements Runnable {
        private final String key;
        private final ContentCachingResponse capture;
        private final CompletableFuture<Result> result = new CompletableFuture<>();

        Job(String key, ContentCachingResponse capture) {
            this.key = key;
            this.capture = capture;
        }

        @Override public void run() {
            active.incrementAndGet();
            Result outcome = Result.CLOSED;
            Throwable failure = null;
            try {
                if (!closed) {
                    var snapshot = capture.finishCapture(true);
                    synchronized (lifecycle) {
                        if (!closed) outcome = snapshot.isPresent() && cache.put(key, snapshot.get())
                                ? Result.STORED : Result.BYPASSED;
                    }
                }
            } catch (RuntimeException | Error problem) {
                failure = problem;
            } finally {
                try { capture.discardCapture(); }
                catch (RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
                active.decrementAndGet();
                pending.decrementAndGet();
            }
            if (failure == null) result.complete(outcome);
            else result.completeExceptionally(failure);
            if (failure instanceof Error fatal) throw fatal;
        }

        void discardClosed() {
            Throwable failure = null;
            try { capture.discardCapture(); }
            catch (RuntimeException | Error cleanup) { failure = cleanup; }
            finally { pending.decrementAndGet(); }
            if (failure == null) result.complete(Result.CLOSED);
            else result.completeExceptionally(failure);
            if (failure != null) rethrow(failure);
        }
    }

    @SuppressWarnings("removal")
    private static Throwable combine(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (primary == cleanup) return primary;
        boolean cleanupFatal = cleanup instanceof VirtualMachineError || cleanup instanceof ThreadDeath;
        boolean primaryFatal = primary instanceof VirtualMachineError || primary instanceof ThreadDeath;
        if ((cleanupFatal && !primaryFatal) || (cleanup instanceof Error && !(primary instanceof Error))) {
            cleanup.addSuppressed(primary);
            return cleanup;
        }
        primary.addSuppressed(cleanup);
        return primary;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error fatal) throw fatal;
        throw (RuntimeException) failure;
    }
}
