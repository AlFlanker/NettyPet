package dev.example.jettyclient.proxy;

/** Отдельный бюджет buffered fetch; лимиты streaming engine этим не расходуются. */
public record ProxyFetchOptions(int maxConcurrent, int decoderThreads, int maxQueued) {
    public ProxyFetchOptions {
        if (maxConcurrent < 1 || decoderThreads < 1 || maxQueued < 1)
            throw new IllegalArgumentException("Fetch concurrency, decoder threads and queue must be positive");
    }

    public static ProxyFetchOptions defaults() { return new ProxyFetchOptions(8, 2, 8); }
}
