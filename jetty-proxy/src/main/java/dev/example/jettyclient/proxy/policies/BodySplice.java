package dev.example.jettyclient.proxy.policies;

import dev.example.jettyclient.proxy.ProxyResponseBody;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Ordered edits; unchanged ranges are borrowed views and insertions reuse encoded literals. */
final class BodySplice {
    private static final int MAX_COMPONENTS = 65_536;
    private final SegmentedText source;
    private final long maxBytes;
    private List<ByteBuffer> output;
    private long copied;
    private long emitted;

    BodySplice(SegmentedText source, long maxBytes) { this.source = source; this.maxBytes = maxBytes; }

    void replace(long from, long to, byte[] insertion) {
        if (from < copied || to < from || to > source.length) throw new IllegalArgumentException("Overlapping rewrite edit");
        if (output == null) output = new ArrayList<>();
        copyTo(from);
        if (insertion.length != 0) add(ByteBuffer.wrap(insertion).asReadOnlyBuffer());
        copied = to;
    }

    ProxyResponseBody finish() {
        if (output == null) {
            if (source.length > maxBytes) throw new RewriteLimitException("Rewrite output exceeds " + maxBytes + " bytes");
            return source.body;
        }
        copyTo(source.length);
        return ProxyResponseBody.of(output);
    }

    private void copyTo(long end) {
        if (end <= copied) return;
        int segment = source.segment(copied);
        while (copied < end) {
            int count = (int)Math.min(end - copied, source.starts[segment + 1] - copied);
            add(source.parts[segment].slice((int)(copied - source.starts[segment]), count).asReadOnlyBuffer());
            copied += count;
            segment++;
        }
    }

    private void add(ByteBuffer span) {
        if (!span.hasRemaining()) return;
        if (span.remaining() > maxBytes - emitted)
            throw new RewriteLimitException("Rewrite output exceeds " + maxBytes + " bytes");
        if (output.size() == MAX_COMPONENTS)
            throw new RewriteLimitException("Rewrite output exceeds " + MAX_COMPONENTS + " components");
        emitted += span.remaining();
        output.add(span);
    }
}
