package dev.example.jettyclient.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic work/ownership checks; no network timing or throughput assertions. */
@Timeout(5)
class ResponseContentBufferTest {
    @Test
    void coalescesOneByteFragmentsIntoBoundedWritesWithoutChangingBytes() {
        byte[] expected = new byte[64 * 1024];
        new Random(73).nextBytes(expected);
        FakeSource source = new FakeSource();
        AtomicInteger releases = new AtomicInteger();
        for (int i = 0; i < expected.length; i++) {
            source.available.add(Content.Chunk.from(ByteBuffer.wrap(new byte[]{expected[i]}),
                    i == expected.length - 1, releases::incrementAndGet));
        }
        ResponseContentBuffer buffer = new ResponseContentBuffer(source, ByteBufferPool.NON_POOLING, 64 * 1024);
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        int writes = 0;
        for (;;) {
            Content.Chunk batch = buffer.read();
            assertNotNull(batch, "All fixture fragments are already available");
            try {
                assertNull(batch.getFailure());
                ByteBuffer bytes = batch.getByteBuffer();
                assertTrue(bytes.remaining() <= 64 * 1024, "One batch must respect the configured byte budget");
                if (bytes.hasRemaining()) {
                    writes++;
                    byte[] copy = new byte[bytes.remaining()];
                    bytes.get(copy);
                    written.writeBytes(copy);
                }
                if (batch.isLast()) break;
            } finally {
                batch.release();
            }
        }
        assertArrayEquals(expected, written.toByteArray());
        // With at most 1024 source reads per batch, a possible carried first fragment allows 65 batches.
        assertTrue(writes <= 65, "Available one-byte chunks must not cause one downstream write per byte: " + writes);
        assertEquals(expected.length, releases.get(), "Every consumed source chunk must be released exactly once");
        assertEquals(0, source.demands.get(), "Already available data must not need asynchronous demand");
    }

    @Test
    void respectsByteBudgetWhenCoalescingLargerSmallFragments() {
        byte[] expected = new byte[64 * 1024];
        new Random(91).nextBytes(expected);
        FakeSource source = new FakeSource();
        AtomicInteger releases = new AtomicInteger();
        for (int offset = 0; offset < expected.length; offset += 512) {
            source.available.add(Content.Chunk.from(ByteBuffer.wrap(Arrays.copyOfRange(expected, offset, offset + 512)),
                    offset + 512 == expected.length, releases::incrementAndGet));
        }
        ResponseContentBuffer buffer = new ResponseContentBuffer(source, ByteBufferPool.NON_POOLING, 4096);
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        int writes = 0;
        for (;;) {
            Content.Chunk batch = buffer.read();
            assertNotNull(batch);
            try {
                ByteBuffer bytes = batch.getByteBuffer();
                assertTrue(bytes.remaining() <= 4096);
                byte[] copy = new byte[bytes.remaining()];
                bytes.get(copy);
                written.writeBytes(copy);
                if (copy.length != 0) writes++;
                if (batch.isLast()) break;
            } finally {
                batch.release();
            }
        }
        assertArrayEquals(expected, written.toByteArray());
        assertEquals(16, writes, "Eight available 512-byte fragments fit each bounded write");
        assertEquals(128, releases.get());
    }

    @Test
    void returnsSingleAvailableSmallFragmentImmediatelyWithoutDemandOrCopy() {
        FakeSource source = new FakeSource();
        AtomicInteger releases = new AtomicInteger();
        Content.Chunk first = Content.Chunk.from(ByteBuffer.wrap(new byte[]{1, 2, 3}), false, releases::incrementAndGet);
        source.available.add(first);
        ResponseContentBuffer buffer = new ResponseContentBuffer(source, ByteBufferPool.NON_POOLING, 4096);

        Content.Chunk result = buffer.read();
        assertSame(first, result);
        assertEquals(0, releases.get(), "Ownership must pass to the downstream write until its completion");
        assertEquals(0, source.demands.get(), "No waiting for a future fragment is allowed merely to enlarge a batch");
        result.release();
        assertEquals(1, releases.get());

        Content.Chunk later = Content.Chunk.from(ByteBuffer.wrap(new byte[]{4}), true, releases::incrementAndGet);
        source.available.add(later);
        Content.Chunk next = buffer.read();
        assertSame(later, next);
        next.release();
        assertEquals(2, releases.get());
    }

    @Test
    void preservesLargeDirectChunkAndItsOwnershipWithoutReadingAhead() {
        FakeSource source = new FakeSource();
        AtomicInteger releases = new AtomicInteger();
        ByteBuffer direct = ByteBuffer.allocateDirect(4096);
        direct.put(new byte[4096]).flip();
        Content.Chunk original = Content.Chunk.from(direct, true, releases::incrementAndGet);
        source.available.add(original);
        ResponseContentBuffer buffer = new ResponseContentBuffer(source, ByteBufferPool.NON_POOLING, 64 * 1024);

        Content.Chunk result = buffer.read();
        assertSame(original, result);
        assertSame(direct, result.getByteBuffer());
        assertEquals(1, source.reads.get(), "A large direct fragment does not need a lookahead for coalescing");
        assertEquals(0, releases.get());
        result.release();
        assertEquals(1, releases.get());
    }

    @Test
    void cancellationReleasesCarryWithoutFailingPossiblyReusedSourceOrReleasingInFlightChunk() {
        FakeSource source = new FakeSource();
        AtomicInteger inFlightReleased = new AtomicInteger();
        AtomicInteger carryReleased = new AtomicInteger();
        AtomicInteger queuedReleased = new AtomicInteger();
        Content.Chunk first = Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false, inFlightReleased::incrementAndGet);
        source.available.add(first);
        source.available.add(Content.Chunk.from(ByteBuffer.wrap(new byte[4096]), false, carryReleased::incrementAndGet));
        source.available.add(Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), true, queuedReleased::incrementAndGet));
        ResponseContentBuffer buffer = new ResponseContentBuffer(source, ByteBufferPool.NON_POOLING, 64 * 1024);
        Content.Chunk inFlight = buffer.read();
        assertSame(first, inFlight);

        IOException cancellation = new IOException("downstream cancelled");
        buffer.fail(cancellation);
        assertNull(source.failure, "A stale content source must not be failed after its connection may have been reused");
        assertEquals(1, carryReleased.get());
        assertEquals(0, queuedReleased.get(), "Unread chunks remain owned by the source until request cancellation");
        assertEquals(0, inFlightReleased.get(), "Cancellation is not a downstream write completion");
        // The exchange aborts its stable Request separately; simulate the source's resulting cleanup here.
        source.fail(cancellation);
        assertEquals(1, queuedReleased.get());
        inFlight.release();
        assertEquals(1, inFlightReleased.get());
    }

    @Test
    void deliversAlreadyAvailablePrefixThenPropagatesCarriedFailureInsteadOfSuccessfulEof() {
        FakeSource source = new FakeSource();
        AtomicInteger releases = new AtomicInteger();
        source.available.add(Content.Chunk.from(ByteBuffer.wrap(new byte[]{1, 2}), false, releases::incrementAndGet));
        source.available.add(Content.Chunk.from(ByteBuffer.wrap(new byte[]{3, 4}), false, releases::incrementAndGet));
        IOException failure = new IOException("truncated upstream body");
        source.available.add(Content.Chunk.from(failure));
        ResponseContentBuffer buffer = new ResponseContentBuffer(source, ByteBufferPool.NON_POOLING, 4096);

        Content.Chunk prefix = buffer.read();
        assertNotNull(prefix);
        try {
            assertNull(prefix.getFailure());
            assertFalse(prefix.isLast(), "An upstream failure after a prefix must not turn that prefix into a complete body");
            byte[] bytes = new byte[prefix.remaining()];
            prefix.getByteBuffer().get(bytes);
            assertArrayEquals(new byte[]{1, 2, 3, 4}, bytes);
        } finally {
            prefix.release();
        }
        assertEquals(2, releases.get());
        Content.Chunk failed = buffer.read();
        assertNotNull(failed);
        try {
            assertSame(failure, failed.getFailure());
        } finally {
            failed.release();
        }
    }

    private static final class FakeSource implements Content.Source {
        final Deque<Content.Chunk> available = new ArrayDeque<>();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger demands = new AtomicInteger();
        Throwable failure;

        @Override public Content.Chunk read() {
            reads.incrementAndGet();
            return available.poll();
        }

        @Override public void demand(Runnable callback) {
            demands.incrementAndGet();
        }

        @Override public void fail(Throwable cause) {
            failure = cause;
            Content.Chunk chunk;
            while ((chunk = available.poll()) != null) chunk.release();
        }
    }
}
