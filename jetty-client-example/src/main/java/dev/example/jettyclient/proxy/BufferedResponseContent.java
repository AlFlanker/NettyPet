package dev.example.jettyclient.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;

/**
 * Opt-in whole-body storage in owned direct slabs; parser chunks never remain retained.
 * Called by one exchange owner. Body views are valid only until {@link #close()}.
 */
final class BufferedResponseContent implements AutoCloseable {
    static final int MAX_SLABS = 65_536;

    private final ByteBufferPool pool;
    private final int chunkBytes;
    private final long maxBytes;
    private final List<RetainableByteBuffer> slabs = new ArrayList<>();
    private ByteBuffer current;
    private long length;
    private boolean closed;

    BufferedResponseContent(ByteBufferPool pool, int chunkBytes, long maxBytes) {
        this.pool = Objects.requireNonNull(pool, "pool");
        if (chunkBytes <= 0) throw new IllegalArgumentException("chunkBytes must be positive");
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must not be negative");
        this.chunkBytes = chunkBytes;
        this.maxBytes = maxBytes;
    }

    /** Checks the complete append before consuming bytes or acquiring another slab. */
    void append(ByteBuffer source) throws IOException {
        open();
        Objects.requireNonNull(source, "source");
        int remaining = source.remaining();
        if (remaining > maxBytes - length)
            throw new LimitExceededException("Response body exceeds rewrite byte limit " + maxBytes);
        // Bounding components also bounds metadata when an application sets an excessive byte limit.
        long nextLength = length + remaining;
        if (nextLength > (long)chunkBytes * MAX_SLABS)
            throw new LimitExceededException("Response body exceeds rewrite slab limit " + MAX_SLABS);
        while (source.hasRemaining()) {
            if (current == null || !current.hasRemaining()) current = acquireSlab();
            int count = Math.min(current.remaining(), source.remaining());
            // Absolute source range avoids a temporary slice and preserves its limit.
            int position = current.position();
            current.put(position, source, source.position(), count);
            current.position(position + count);
            source.position(source.position() + count);
            length += count;
        }
    }

    private ByteBuffer acquireSlab() {
        RetainableByteBuffer owner = pool.acquire(chunkBytes, true);
        try {
            ByteBuffer buffer = owner.getByteBuffer();
            buffer.clear();
            buffer.limit(chunkBytes);
            slabs.add(owner);
            return buffer;
        } catch (RuntimeException | Error failure) {
            owner.release();
            throw failure;
        }
    }

    /** A cursor-independent snapshot; no body bytes are copied here. */
    ProxyResponseBody body() {
        open();
        List<ByteBuffer> views = new ArrayList<>(slabs.size());
        for (RetainableByteBuffer slab : slabs) {
            ByteBuffer view = slab.getByteBuffer().asReadOnlyBuffer();
            view.flip();
            views.add(view);
        }
        return ProxyResponseBody.of(views);
    }

    private void open() {
        if (closed) throw new IllegalStateException("Buffered response content is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        current = null;
        Throwable failure = null;
        for (RetainableByteBuffer slab : slabs) {
            try { slab.release(); }
            catch (RuntimeException | Error releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else if (failure != releaseFailure) failure.addSuppressed(releaseFailure);
            }
        }
        slabs.clear();
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    static final class LimitExceededException extends IOException {
        LimitExceededException(String message) { super(message); }
    }
}
