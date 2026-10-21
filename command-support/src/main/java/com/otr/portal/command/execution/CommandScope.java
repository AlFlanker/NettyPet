package com.otr.portal.command.execution;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One monotonic budget and cancellation winner for a logical command, including
 * admission, attempts and retry backoff. Gates are checked on the thread doing
 * the work, so a delayed timeout scheduler cannot authorize an expired attempt
 * or turn a late completion into success.
 */
public final class CommandScope {
    private final long startedAt = System.nanoTime();
    private final long timeoutNanos;
    private final Consumer<CancellationReason> onCancellation;
    private CancellationReason cancellation;
    private boolean decided;

    public CommandScope(Duration timeout, Consumer<CancellationReason> onCancellation) {
        timeoutNanos = validateTimeout(timeout);
        this.onCancellation = Objects.requireNonNull(onCancellation, "onCancellation");
    }

    /** Validates public executor configurations before any execution resources are retained. */
    static long validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        final long nanos;
        try { nanos = timeout.toNanos(); }
        catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Command timeout must fit a monotonic nanosecond budget", overflow);
        }
        if (nanos < 1_000_000)
            throw new IllegalArgumentException("Command timeout must be at least one millisecond");
        return nanos;
    }

    /** Returns the remaining total budget; it never resets for a retry. */
    public long remainingNanos() {
        return timeoutNanos - (System.nanoTime() - startedAt);
    }

    /** Gate immediately before beginning any attempt or accepting an intermediate result. */
    public boolean isActive() { return gate(false); }

    /** Atomically selects the logical terminal result if it is still within the budget. */
    boolean tryComplete() { return gate(true); }

    private boolean gate(boolean complete) {
        boolean expired = false;
        synchronized (this) {
            if (decided) return false;
            if (remainingNanos() <= 0) {
                cancellation = CancellationReason.TIMEOUT;
                decided = true;
                expired = true;
            } else {
                if (complete) decided = true;
                return true;
            }
        }
        if (expired) onCancellation.accept(CancellationReason.TIMEOUT);
        return false;
    }

    /** First terminal/cancellation decision wins, including simultaneous timeout and shutdown. */
    public void cancel(CancellationReason reason) {
        Objects.requireNonNull(reason, "reason");
        synchronized (this) {
            if (decided) return;
            cancellation = reason;
            decided = true;
        }
        onCancellation.accept(reason);
    }

    public synchronized CancellationReason cancellationReason() { return cancellation; }

}
