package dev.example.jettyclient.proxy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.AbstractList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProxyResponseBodyTest {
    @Test
    void freezesRemainingRangesAndExposesIndependentReadOnlyCursors() {
        ByteBuffer source = ByteBuffer.wrap(new byte[] {99, 1, 2, 3, 4, 98});
        source.order(ByteOrder.LITTLE_ENDIAN).position(1).limit(5);
        ProxyResponseBody body = ProxyResponseBody.of(source);
        source.clear();

        assertEquals(4, body.length());
        assertEquals(1, body.bufferCount());
        ByteBuffer first = body.buffers().getFirst();
        assertEquals(ByteOrder.LITTLE_ENDIAN, first.order());
        assertEquals(1, first.get());
        first.limit(2);
        ByteBuffer second = body.buffers().getFirst();
        assertNotSame(first, second);
        assertEquals(0, second.position());
        assertEquals(4, second.limit());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes(second));
        assertThrows(ReadOnlyBufferException.class, () -> second.put(0, (byte)9));
        assertThrows(UnsupportedOperationException.class, () -> body.buffers().clear());
    }

    @Test
    void outputCanBorrowInputSlicesWithoutCopyingBackingMemory() {
        ByteBuffer owner = ByteBuffer.allocateDirect(6);
        owner.put(new byte[] {1, 2, 3, 4, 5, 6}).flip();
        ProxyResponseBody input = ProxyResponseBody.of(owner);
        ByteBuffer borrowed = input.buffers().getFirst();
        borrowed.position(2).limit(5);
        ProxyResponseBody output = ProxyResponseBody.of(List.of(
                ByteBuffer.wrap(new byte[] {90}), borrowed));
        borrowed.clear();

        assertEquals(4, output.length());
        assertEquals(2, output.bufferCount());
        assertEquals(6, input.length());
        assertEquals(6, input.buffers().getFirst().remaining());
        assertTrue(output.buffers().get(1).isDirect());
        assertArrayEquals(new byte[] {3, 4, 5}, bytes(output.buffers().get(1)));
        // Deliberately mutate the owner only to prove the API borrows its storage.
        // Application code must keep that storage unchanged while an exchange uses it.
        owner.put(3, (byte)44);
        assertArrayEquals(new byte[] {3, 44, 5}, bytes(output.buffers().get(1)));
        assertThrows(ReadOnlyBufferException.class,
                () -> output.buffers().get(1).put(0, (byte)0));
    }

    @Test
    void excessiveComponentCountIsRejectedBeforeReadingAnyComponent() {
        List<ByteBuffer> oversized = new AbstractList<>() {
            @Override public int size() { return 65_537; }
            @Override public ByteBuffer get(int index) {
                throw new AssertionError("An oversized body must be rejected before iteration");
            }
        };
        assertThrows(ProxyResponseBody.TooManyBuffersException.class,
                () -> ProxyResponseBody.of(oversized));
    }

    @Test
    void acceptsEmptyOutputWithoutInventingAComponent() {
        ProxyResponseBody body = ProxyResponseBody.of(List.of());
        assertEquals(0, body.length());
        assertEquals(0, body.bufferCount());
        assertTrue(body.buffers().isEmpty());
    }

    private static byte[] bytes(ByteBuffer source) {
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return bytes;
    }
}
