package com.otr.portal.proxy.caching;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Objects;

/**
 * Writes an immutable cached byte array without blocking the Servlet thread.
 * The caller prepares headers and an async timeout before handing ownership here.
 * One chunk at a time is offered only while the container reports readiness.
 */
public final class CachedResponseWriter implements WriteListener, AsyncListener {
    public static final int CHUNK_BYTES = 64 * 1024;

    private final AsyncContext async;
    private final ServletOutputStream output;
    private final Runnable completion;
    private byte[] content;
    private int offset;
    private boolean flushed;
    private boolean terminal;

    private CachedResponseWriter(AsyncContext async, ServletOutputStream output, byte[] content, Runnable completion) {
        this.async = Objects.requireNonNull(async, "async");
        this.output = Objects.requireNonNull(output, "output");
        this.content = Objects.requireNonNull(content, "content");
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    public static void write(AsyncContext async, HttpServletResponse response, byte[] content, Runnable completion)
            throws IOException {
        Objects.requireNonNull(async, "async");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(completion, "completion");
        ServletOutputStream output;
        try {
            output = response.getOutputStream();
        } catch (IOException | RuntimeException | Error failure) {
            completeAfterSetupFailure(async, completion, failure);
            throw failure;
        }
        var writer = new CachedResponseWriter(async, output, content, completion);
        try {
            async.addListener(writer);
            writer.output.setWriteListener(writer);
        } catch (RuntimeException | Error failure) {
            writer.cleanup(failure, true);
            throw failure;
        }
    }

    /** Container callbacks serialize state; terminal lifecycle callbacks can race them. */
    @Override public synchronized void onWritePossible() {
        if (terminal) return;
        try {
            while (!terminal && offset < content.length) {
                if (!ready()) return;
                int count = Math.min(CHUNK_BYTES, content.length - offset);
                output.write(content, offset, count);
                if (terminal) return;
                offset += count;
            }
            if (!ready()) return;
            if (!flushed) {
                flushed = true;
                output.flush();
                if (terminal) return;
            }
            if (ready()) finish(true);
        } catch (IOException | RuntimeException failure) {
            cleanup(failure, true);
        } catch (Error fatal) {
            cleanup(fatal, true);
            throw fatal;
        }
    }

    private boolean ready() { return !terminal && output.isReady() && !terminal; }

    @Override public synchronized void onError(Throwable failure) {
        cleanup(failure, true);
        if (failure instanceof Error fatal) throw fatal;
    }
    @Override public synchronized void onError(AsyncEvent event) { onError(event.getThrowable()); }
    @Override public synchronized void onTimeout(AsyncEvent event) { finish(true); }
    @Override public synchronized void onComplete(AsyncEvent event) { finish(false); }
    @Override public synchronized void onStartAsync(AsyncEvent event) { finish(false); }

    private synchronized void cleanup(Throwable primary, boolean completeAsync) {
        try { finish(completeAsync); }
        catch (RuntimeException | Error cleanup) {
            if (primary == null) throw cleanup;
            Throwable selected = combine(primary, cleanup);
            if (selected != primary && selected instanceof Error fatal) throw fatal;
        }
    }

    private static void completeAfterSetupFailure(AsyncContext async, Runnable completion, Throwable primary) {
        Throwable failure = primary;
        try { completion.run(); }
        catch (RuntimeException | Error callbackFailure) { failure = combine(failure, callbackFailure); }
        try { async.complete(); }
        catch (IllegalStateException alreadyCompleted) { /* no live context remains */ }
        catch (RuntimeException | Error completionFailure) { failure = combine(failure, completionFailure); }
        if (failure != primary && failure instanceof Error fatal) throw fatal;
    }

    private synchronized void finish(boolean completeAsync) {
        if (terminal) return;
        terminal = true;
        content = null;
        Throwable failure = null;
        try { completion.run(); }
        catch (RuntimeException | Error callbackFailure) { failure = callbackFailure; }
        if (completeAsync) {
            try { async.complete(); }
            catch (IllegalStateException alreadyCompleted) { /* container owns terminal completion */ }
            catch (RuntimeException | Error completionFailure) { failure = combine(failure, completionFailure); }
        }
        if (failure instanceof Error fatal) throw fatal;
        if (failure instanceof RuntimeException ordinary) throw ordinary;
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first == next) return first;
        boolean nextFatal = next instanceof VirtualMachineError || next instanceof ThreadDeath;
        boolean firstFatal = first instanceof VirtualMachineError || first instanceof ThreadDeath;
        if ((nextFatal && !firstFatal) || (next instanceof Error && !(first instanceof Error))) {
            next.addSuppressed(first);
            return next;
        }
        first.addSuppressed(next);
        return first;
    }
}
