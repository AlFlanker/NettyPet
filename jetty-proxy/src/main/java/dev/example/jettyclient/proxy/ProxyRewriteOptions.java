package dev.example.jettyclient.proxy;

/**
 * Independent CPU/queue and conservative payload budget for optional response rewriting.
 * The byte budget covers declared input/output limits plus one coalescing chunk per exchange;
 * it is not an RSS bound and excludes allocator rounding, parser/TLS and application metadata.
 */
public record ProxyRewriteOptions(int workers, int maxQueued, long maxBufferedBytes) {
    public ProxyRewriteOptions {
        if (workers < 1 || maxQueued < 1 || maxBufferedBytes < 1)
            throw new IllegalArgumentException("Rewrite workers, queue and buffered byte budget must be positive");
    }

    public static ProxyRewriteOptions defaults() { return new ProxyRewriteOptions(2, 8, 128L * 1024 * 1024); }
}
