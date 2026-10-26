package dev.example.jettyclient.proxy.policies;

import dev.example.jettyclient.proxy.ProxyResponseBody;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/** Indexed borrowed body; sequential byte access does not scan the segment list. */
final class SegmentedText {
    final ProxyResponseBody body;
    final ByteBuffer[] parts;
    final long[] starts;
    final long length;
    private int cached;

    SegmentedText(ProxyResponseBody body) {
        this.body = body;
        List<ByteBuffer> buffers = body.buffers().stream().filter(ByteBuffer::hasRemaining).toList();
        parts = buffers.toArray(ByteBuffer[]::new);
        starts = new long[parts.length + 1];
        for (int i = 0; i < parts.length; i++) starts[i + 1] = starts[i] + parts[i].remaining();
        length = starts[parts.length];
    }

    byte get(long at) {
        if (at < 0 || at >= length) throw new IndexOutOfBoundsException();
        if (cached >= parts.length || at < starts[cached] || at >= starts[cached + 1]) cached = segment(at);
        return parts[cached].get((int)(at - starts[cached]));
    }

    int segment(long at) {
        int found = Arrays.binarySearch(starts, at);
        return found >= 0 ? found : -found - 2;
    }

    boolean startsWith(long at, byte[] prefix) {
        if (at < 0 || prefix.length > length - at) return false;
        for (int i = 0; i < prefix.length; i++) if (get(at + i) != prefix[i]) return false;
        return true;
    }

    long find(Needle needle, long from) {
        if (needle.bytes.length == 0) return Math.min(from, length);
        int matched = 0;
        for (long at = from; at < length; at++) {
            byte value = get(at);
            while (matched > 0 && value != needle.bytes[matched]) matched = needle.failure[matched - 1];
            if (value == needle.bytes[matched] && ++matched == needle.bytes.length)
                return at + 1 - needle.bytes.length;
        }
        return -1;
    }

    long findByte(byte needle, long from) {
        for (long at = from; at < length; at++) if (get(at) == needle) return at;
        return -1;
    }

    static final class Needle {
        final byte[] bytes;
        final int[] failure;
        Needle(byte[] bytes) {
            this.bytes = bytes;
            failure = new int[bytes.length];
            for (int i = 1, matched = 0; i < bytes.length; i++) {
                while (matched > 0 && bytes[i] != bytes[matched]) matched = failure[matched - 1];
                if (bytes[i] == bytes[matched]) failure[i] = ++matched;
            }
        }
    }
}
