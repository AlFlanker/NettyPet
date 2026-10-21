package dev.example.proxy.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет накопление метрик, обработку отмены и устойчивость счётчиков к некорректным измерениям. */
class ProxyMetricsTest {
    @Test
    void distinguishesAdmissionRejectionAndCompletedOutcomes() {
        ProxyMetrics metrics = new ProxyMetrics();
        metrics.admitted();
        metrics.finished(200, false, 100, 200, 50, 16);
        metrics.admitted();
        metrics.finished(502, false, 30, 10, 80, 32);
        metrics.admitted();
        metrics.finished(0, true, 7, 0, 10, 0);
        metrics.rejected(403);
        metrics.rejected(503);

        assertEquals(new ProxyMetrics.Snapshot(3, 2, 3, 1, 2, 137, 210, 140, 32),
                metrics.snapshot());
    }

    @Test
    @Timeout(10)
    void concurrentWritersPreserveTotalsAndMaximumAfterTheyFinish() throws Exception {
        ProxyMetrics metrics = new ProxyMetrics();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> writers = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int worker = 0; worker < 2; worker++) {
                int workerIndex = worker;
                writers.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int i = 1; i <= 1_000; i++) {
                        metrics.admitted();
                        metrics.finished(200, false, 3, 5, 7, workerIndex * 1_000L + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> writer : writers) writer.get(5, TimeUnit.SECONDS);
        }

        assertEquals(new ProxyMetrics.Snapshot(2_000, 0, 2_000, 0, 0,
                6_000, 10_000, 14_000, 2_000), metrics.snapshot());
    }

    @Test
    void invalidTelemetryCannotReduceCountersOrBreakCleanup() {
        ProxyMetrics metrics = new ProxyMetrics();
        metrics.finished(200, false, 3, 5, 7, 11);
        ProxyMetrics.Snapshot previous = metrics.snapshot();
        metrics.finished(0, true, -1, -2, -3, -4);

        assertEquals(new ProxyMetrics.Snapshot(0, 0, 2, 1, 0, 3, 5, 7, 11), metrics.snapshot());
        assertEquals(new ProxyMetrics.Snapshot(0, 0, 1, 0, 0, 3, 5, 7, 11), previous,
                "A previously returned snapshot must stay immutable");
    }
}
