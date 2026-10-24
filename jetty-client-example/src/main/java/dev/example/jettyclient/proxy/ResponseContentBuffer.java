package dev.example.jettyclient.proxy;

import java.nio.ByteBuffer;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;

/**
 * Собирает только уже доступные мелкие фрагменты, не ожидая следующих байтов.
 * Один read ограничен размером порции и числом фрагментов; большой chunk не копируется.
 * Вызывается одним владельцем, без незавершённого demand и downstream write.
 */
final class ResponseContentBuffer {
    static final int SMALL_FRAGMENT_BYTES = 1024;
    static final int MAX_FRAGMENTS = 1024;

    private final Content.Source source;
    private final ByteBufferPool pool;
    private final int chunkBytes;
    private Content.Chunk carry;
    private boolean sourceEnded;

    ResponseContentBuffer(Content.Source source, ByteBufferPool pool, int chunkBytes) {
        this.source = source;
        this.pool = pool;
        this.chunkBytes = chunkBytes;
    }

    /** Returns a caller-owned chunk, or null; every returned chunk must be released. */
    Content.Chunk read() {
        Content.Chunk first = next();
        if (first == null || Content.Chunk.isFailure(first) || first.isLast() || !first.hasRemaining()
                || first.remaining() >= SMALL_FRAGMENT_BYTES) return first;

        Content.Chunk second;
        try { second = next(); }
        catch (Throwable failure) { first.release(); throw failure; }
        // A single event is sent immediately. Never demand or wait merely to form a batch.
        if (second == null) return first;
        if (Content.Chunk.isFailure(second) || !second.hasRemaining()
                || second.remaining() >= SMALL_FRAGMENT_BYTES
                || second.remaining() > chunkBytes - first.remaining()) {
            carry = second;
            return first;
        }

        RetainableByteBuffer owner;
        try { owner = pool.acquire(chunkBytes, true); }
        catch (Throwable failure) { first.release(); second.release(); throw failure; }
        ByteBuffer batch = owner.getByteBuffer();
        try {
            batch.clear();
            batch.limit(chunkBytes);
            boolean last = second.isLast();
            Content.Chunk copying = first;
            first = null;
            copyAndRelease(batch, copying);
            copying = second;
            second = null;
            copyAndRelease(batch, copying);
            int fragments = 2;
            while (!last && batch.hasRemaining() && fragments < MAX_FRAGMENTS) {
                Content.Chunk chunk = next();
                if (chunk == null) break;
                fragments++;
                if (Content.Chunk.isFailure(chunk)) {
                    carry = chunk;
                    break;
                }
                if (!chunk.hasRemaining()) {
                    last = chunk.isLast();
                    chunk.release();
                    continue;
                }
                if (chunk.remaining() >= SMALL_FRAGMENT_BYTES || chunk.remaining() > batch.remaining()) {
                    carry = chunk;
                    break;
                }
                last = chunk.isLast();
                copyAndRelease(batch, chunk);
            }
            batch.flip();
            // The returned chunk owns this existing pool reference (no extra retain).
            return Content.Chunk.from(owner, last);
        } catch (Throwable failure) {
            owner.release();
            throw failure;
        } finally {
            if (first != null) first.release();
            if (second != null) second.release();
        }
    }

    private Content.Chunk next() {
        if (carry != null) {
            Content.Chunk chunk = carry;
            carry = null;
            return chunk;
        }
        if (sourceEnded) return Content.Chunk.EOF;
        Content.Chunk chunk = source.read();
        if (chunk != null && chunk.isLast()) sourceEnded = true;
        return chunk;
    }

    private static void copyAndRelease(ByteBuffer target, Content.Chunk chunk) {
        try { target.put(chunk.getByteBuffer()); }
        finally { chunk.release(); }
    }

    void demand(Runnable callback) { source.demand(callback); }

    void fail(Throwable failure) {
        // Only dispose references owned by this helper. A pending demand may have
        // parsed EOF and returned the connection to the pool before our read sees
        // EOF. Failing that old source could affect the next exchange on its receiver.
        // The exchange aborts its stable Request to cancel active network I/O.
        sourceEnded = true;
        if (carry != null) {
            carry.release();
            carry = null;
        }
    }
}
