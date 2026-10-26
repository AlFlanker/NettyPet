package dev.example.jettyclient.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.List;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BufferedResponseContentTest {
    @Test
    void copiesOnlyRemainingBytesIntoDirectSlabsAndAdvancesSourceWithoutChangingLimit() throws Exception {
        RecordingPool pool = new RecordingPool();
        try (BufferedResponseContent content = new BufferedResponseContent(pool, 8, 100)) {
            ByteBuffer source = ByteBuffer.wrap(new byte[] {99, 1, 2, 3, 4, 5, 98});
            source.position(1).limit(6);
            content.append(source);
            assertEquals(6, source.position());
            assertEquals(6, source.limit());
            content.append(ByteBuffer.wrap(new byte[] {6, 7, 8, 9, 10, 11, 12}).asReadOnlyBuffer());
            content.append(ByteBuffer.wrap(new byte[] {13, 14, 15, 16, 17}));

            assertEquals(3, pool.acquired);
            assertEquals(0, pool.released);
            ProxyResponseBody body = content.body();
            assertEquals(17, body.length());
            assertEquals(List.of(8, 8, 1), body.buffers().stream().map(ByteBuffer::remaining).toList());
            assertTrue(body.buffers().stream().allMatch(ByteBuffer::isDirect));
            assertTrue(body.buffers().stream().allMatch(ByteBuffer::isReadOnly));
            byte[] expected = new byte[17];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte)(i + 1);
            assertArrayEquals(expected, bytes(body));
        }
        assertEquals(3, pool.released);
        assertEquals(0, pool.outstanding());
    }

    @Test
    void tinyFragmentsDoNotRetainOneAllocationPerParserChunkOrBorrowSourceMemory() throws Exception {
        RecordingPool pool = new RecordingPool();
        byte[] expected = new byte[10_007];
        ByteBuffer fragment = ByteBuffer.allocate(1);
        try (BufferedResponseContent content = new BufferedResponseContent(pool, 1024, expected.length)) {
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (byte)(i * 37);
                fragment.clear().put(expected[i]).flip();
                content.append(fragment);
            }
            fragment.clear().put((byte)0);
            assertEquals(10, pool.acquired, "Storage grows with bytes, not with HTTP chunk count");
            assertEquals(0, pool.released);
            assertArrayEquals(expected, bytes(content.body()));
        }
        assertEquals(10, pool.released);
    }

    @Test
    void snapshotsFreezeWrittenRangesAndHaveIndependentReadOnlyCursors() throws Exception {
        RecordingPool pool = new RecordingPool();
        try (BufferedResponseContent content = new BufferedResponseContent(pool, 8, 100)) {
            content.append(ByteBuffer.wrap(new byte[] {1, 2, 3}));
            ProxyResponseBody first = content.body();
            ByteBuffer cursor = first.buffers().getFirst();
            assertThrows(ReadOnlyBufferException.class, () -> cursor.put(0, (byte)9));
            cursor.get();
            assertEquals(3, first.buffers().getFirst().remaining());
            assertThrows(UnsupportedOperationException.class, () -> first.buffers().clear());

            content.append(ByteBuffer.wrap(new byte[] {4, 5, 6, 7, 8, 9}));
            assertEquals(3, first.length());
            assertArrayEquals(new byte[] {1, 2, 3}, bytes(first));
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, bytes(content.body()));
        }
    }

    @Test
    void byteLimitFailureIsAtomicAndPreviouslyOwnedSlabsRemainUntilClose() throws Exception {
        RecordingPool pool = new RecordingPool();
        BufferedResponseContent content = new BufferedResponseContent(pool, 4, 7);
        try {
            content.append(ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6}));
            ByteBuffer excessive = ByteBuffer.wrap(new byte[] {99, 7, 8, 98});
            excessive.position(1).limit(3);
            assertThrows(BufferedResponseContent.LimitExceededException.class, () -> content.append(excessive));
            assertEquals(1, excessive.position());
            assertEquals(3, excessive.limit());
            assertEquals(2, pool.acquired);
            assertEquals(0, pool.released);
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, bytes(content.body()));

            content.append(ByteBuffer.wrap(new byte[] {7}));
            assertEquals(7, content.body().length());
        } finally {
            content.close();
            content.close();
        }
        assertEquals(2, pool.released);
    }

    @Test
    void componentLimitRejectsExcessiveConfigurationBeforeAnyAllocationOrConsumption() {
        RecordingPool pool = new RecordingPool();
        ByteBuffer source = ByteBuffer.allocate(BufferedResponseContent.MAX_SLABS + 1);
        try (BufferedResponseContent content = new BufferedResponseContent(pool, 1, Long.MAX_VALUE)) {
            assertThrows(BufferedResponseContent.LimitExceededException.class, () -> content.append(source));
            assertEquals(0, source.position());
            assertEquals(0, pool.acquired);
            assertEquals(0, content.body().length());
        }
    }

    @Test
    void allocationFailureKeepsPreviouslyAcquiredSlabsOwnedAndReleasable() {
        RecordingPool pool = new RecordingPool();
        pool.failAcquireAt = 2;
        ByteBuffer source = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5});
        try (BufferedResponseContent content = new BufferedResponseContent(pool, 4, 20)) {
            assertThrows(IllegalStateException.class, () -> content.append(source));
            assertEquals(4, source.position());
            assertEquals(5, source.limit());
            assertEquals(1, pool.outstanding());
            assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes(content.body()));
        }
        assertEquals(1, pool.released);
        assertEquals(0, pool.outstanding());
    }

    @Test
    void emptyBodyNeedsNoAllocationAndClosedStorageCannotBeUsed() throws Exception {
        RecordingPool pool = new RecordingPool();
        BufferedResponseContent content = new BufferedResponseContent(pool, 8, 0);
        content.append(ByteBuffer.allocate(0));
        assertEquals(0, content.body().length());
        assertTrue(content.body().buffers().isEmpty());
        assertEquals(0, pool.acquired);
        assertThrows(IOException.class, () -> content.append(ByteBuffer.wrap(new byte[] {1})));
        content.close();
        content.close();
        assertThrows(IllegalStateException.class, content::body);
        assertThrows(IllegalStateException.class, () -> content.append(ByteBuffer.allocate(0)));
    }

    @Test
    void roundedPoolCapacityAndStaleBytesNeverLeakIntoBodyViews() throws Exception {
        RecordingPool pool = new RecordingPool();
        pool.extraCapacity = 17;
        try (BufferedResponseContent content = new BufferedResponseContent(pool, 4, 100)) {
            content.append(ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5}));
            assertEquals(2, pool.acquired);
            assertEquals(List.of(4, 1), content.body().buffers().stream().map(ByteBuffer::remaining).toList());
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, bytes(content.body()));
        }
        assertEquals(2, pool.released);
    }

    @Test
    void closeStillReleasesEverySlabOnceWhenOnePoolReleaseFails() throws Exception {
        RecordingPool pool = new RecordingPool();
        pool.failReleaseAt = 1;
        BufferedResponseContent content = new BufferedResponseContent(pool, 4, 100);
        content.append(ByteBuffer.wrap(new byte[9]));
        assertThrows(IllegalStateException.class, content::close);
        assertEquals(3, pool.released);
        content.close();
        assertEquals(3, pool.released);
        assertEquals(0, pool.outstanding());
    }

    private static byte[] bytes(ProxyResponseBody body) {
        byte[] result = new byte[Math.toIntExact(body.length())];
        int offset = 0;
        for (ByteBuffer buffer : body.buffers()) {
            int count = buffer.remaining();
            buffer.get(result, offset, count);
            offset += count;
        }
        return result;
    }

    /** Exact acquisition/release accounting without relying on GC or a pool's idle cache. */
    private static final class RecordingPool implements ByteBufferPool {
        private int attempts;
        private int acquired;
        private int released;
        private int failAcquireAt = -1;
        private int failReleaseAt = -1;
        private int extraCapacity;

        @Override
        public RetainableByteBuffer.Mutable acquire(int size, boolean direct) {
            assertTrue(direct, "Whole-body rewrite storage must request direct memory");
            if (++attempts == failAcquireAt) throw new IllegalStateException("Pool acquisition failed");
            ByteBuffer buffer = ByteBuffer.allocateDirect(size + extraCapacity);
            byte[] stale = new byte[buffer.capacity()];
            Arrays.fill(stale, (byte)99);
            buffer.put(stale).flip();
            acquired++;
            return RetainableByteBuffer.wrap(buffer, () -> {
                if (++released == failReleaseAt) throw new IllegalStateException("Pool release failed");
            });
        }

        int outstanding() { return acquired - released; }

        @Override public void clear() { }
    }
}
