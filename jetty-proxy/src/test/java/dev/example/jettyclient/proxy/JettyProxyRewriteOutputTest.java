package dev.example.jettyclient.proxy;

import dev.example.jettyclient.proxy.policies.LinkRewritePolicy;
import dev.example.jettyclient.proxy.policies.LinkRewriteSettings;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.ee11.servlet.ServletChannel;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Ready rewrite fragments are batched without weakening downstream ownership or byte integrity. */
@Timeout(10)
class JettyProxyRewriteOutputTest {
    @Test
    @Timeout(20)
    void eightMebibyteCssWithTenThousandActualLinkReplacementsAvoidsTinyWriteAmplification() throws Exception {
        int inputSize = 8 * 1024 * 1024;
        int links = 10_000;
        String prefix = "a{background:url(http://backend/base/x)}\n".repeat(links);
        String css = prefix + " ".repeat(inputSize - prefix.length());
        byte[] input = css.getBytes(StandardCharsets.UTF_8);
        byte[] expected = css.replace("http://backend/base/", "https://frontend/portal/").getBytes(StandardCharsets.UTF_8);
        var policy = new LinkRewritePolicy(new LinkRewriteSettings(URI.create("http://backend/base"),
                URI.create("https://frontend/portal"), inputSize, 12L * 1024 * 1024));
        var plan = ProxyRequestPlan.builder(URI.create("http://backend/base/style.css")).responseBody(policy).build();
        try (Fixture fixture = new Fixture(65_536)) {
            ProxyCall call = fixture.reply(input, plan, HttpFields.build()
                    .put("Content-Type", "text/css; charset=UTF-8").asImmutable());
            await().pollInSameThread().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                fixture.owner.executor.drain();
                assertTrue(call.completion().toCompletableFuture().isDone());
            });

            ProxyExchangeResult result = call.completion().toCompletableFuture().join();
            assertTrue(result.success());
            assertEquals(expected.length, result.downloadedBytes());
            assertArrayEquals(hash(expected), hash(fixture.output.bytes.toByteArray()));
            int writes = fixture.output.writes.get();
            assertTrue(writes < 200, "8 MiB CSS must use fewer than 200 body writes; actual=" + writes);
            assertEquals(0, fixture.engine.reservedRewriteBytes());
            assertEquals(0, fixture.pool.outstanding.get());
            assertEquals(fixture.pool.acquired.get(), fixture.pool.released.get());
            assertEquals(0, fixture.engine.activeRequests());
            System.out.println("8MiB CSS links=" + links + ", writerCalls=" + writes
                    + ", previousWriterCalls=20124, sha256Matches=true, reservedBytes=0, poolOutstanding=0");
        }
    }

    @Test
    void thousandsOfReadySmallSpansUseChunkSizedWritesAndPreserveTheWholeBodySha256() throws Exception {
        byte[] bytes = random(12 * 1024 + 137);
        try (Fixture fixture = new Fixture()) {
            ProxyCall call = fixture.reply(bytes, splitToBytes());
            fixture.until(() -> assertTrue(call.completion().toCompletableFuture().isDone()));

            assertTrue(call.completion().toCompletableFuture().join().success());
            assertArrayEquals(hash(bytes), hash(fixture.output.bytes.toByteArray()));
            int chunks = (bytes.length + 1023) / 1024;
            assertEquals(chunks, fixture.output.writes.get(), "A byte-sized span must not become a separate write");
            assertEquals(chunks * 2, fixture.pool.acquired.get(), "Input slabs plus one temporary batch per write");
            assertEquals(fixture.pool.acquired.get(), fixture.pool.released.get());
            assertEquals(0, fixture.pool.outstanding.get());
            assertEquals(0, fixture.engine.reservedRewriteBytes());
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void largeOutputSpansAndTheirShortTailsRemainBorrowed() throws Exception {
        byte[] original = random(32);
        byte[] output = random(2 * 1024 + 123);
        byte[] suffix = {3, 1, 4, 1, 5};
        try (Fixture fixture = new Fixture()) {
            ProxyCall call = fixture.reply(original, (metadata, input) -> ProxyResponseBody.of(List.of(
                    ByteBuffer.wrap(output), ByteBuffer.wrap(suffix))));
            fixture.until(() -> assertTrue(call.completion().toCompletableFuture().isDone()));

            byte[] expected = Arrays.copyOf(output, output.length + suffix.length);
            System.arraycopy(suffix, 0, expected, output.length, suffix.length);
            assertArrayEquals(expected, fixture.output.bytes.toByteArray());
            assertEquals(1, fixture.pool.acquired.get(), "Only the input slab: a large span's tail is not copied into a batch");
            assertEquals(4, fixture.output.writes.get());
            assertEquals(0, fixture.pool.outstanding.get());
            assertTrue(call.completion().toCompletableFuture().join().success());
        }
    }

    @Test
    void aSlowWriterRetainsTheBatchAndReservationUntilItsReadinessCallback() throws Exception {
        byte[] bytes = random(4 * 1024 + 37);
        try (Fixture fixture = new Fixture()) {
            fixture.output.blockAfterWrite = true;
            ProxyCall call = fixture.reply(bytes, splitToBytes());
            fixture.until(() -> assertEquals(1, fixture.output.writes.get()));
            int inputSlabs = (bytes.length + 1023) / 1024;
            assertEquals(inputSlabs + 1, fixture.pool.outstanding.get(), "Input and current batch remain owned");
            long reserved = fixture.engine.reservedRewriteBytes();
            assertTrue(reserved > 0);
            fixture.owner.executor.drain();
            assertEquals(1, fixture.output.writes.get(), "Readiness must gate the next write");
            assertEquals(reserved, fixture.engine.reservedRewriteBytes());
            assertFalse(call.completion().toCompletableFuture().isDone());

            fixture.output.blockAfterWrite = false;
            fixture.output.ready = true;
            fixture.output.listener.onWritePossible();
            fixture.until(() -> assertTrue(call.completion().toCompletableFuture().isDone()));

            assertArrayEquals(hash(bytes), hash(fixture.output.bytes.toByteArray()));
            assertEquals(0, fixture.pool.outstanding.get());
            assertEquals(0, fixture.engine.reservedRewriteBytes());
        }
    }

    @Test
    void cancellationReturnsTheBatchOnceAndLeavesTheContainersPendingArrayUnchanged() throws Exception {
        byte[] bytes = random(3 * 1024 + 71);
        try (MockedStatic<ServletContextRequest> contexts = mockStatic(ServletContextRequest.class);
             Fixture fixture = new Fixture()) {
            ServletChannel channel = fixture.bindChannel(contexts);
            fixture.output.blockAfterWrite = true;
            ProxyCall call = fixture.reply(bytes, splitToBytes());
            fixture.until(() -> assertEquals(1, fixture.output.writes.get()));
            byte[] pending = fixture.output.pendingArray;
            assertNotNull(pending);
            byte[] originalPending = pending.clone();
            IOException cancelled = new IOException("Downstream no longer needs this body");

            call.cancel(cancelled);
            fixture.owner.executor.drain();

            assertTrue(call.completion().toCompletableFuture().isDone());
            assertSame(cancelled, call.completion().toCompletableFuture().join().failure());
            assertEquals(0, fixture.engine.activeRequests());
            assertEquals(0, fixture.engine.reservedRewriteBytes());
            assertEquals(0, fixture.pool.outstanding.get());
            assertEquals(fixture.pool.acquired.get(), fixture.pool.released.get());
            assertArrayEquals(originalPending, pending, "Releasing pooled batches must not reuse the Servlet's unpooled array");
            fixture.output.ready = true;
            fixture.output.listener.onWritePossible();
            fixture.owner.executor.drain();
            assertEquals(1, fixture.output.writes.get());
            verify(channel).abort(cancelled);
        }
    }

    @Test
    void aThrowingBatchReleaserDoesNotStrandInputOrBudget() throws Exception {
        byte[] bytes = random(3 * 1024);
        try (MockedStatic<ServletContextRequest> contexts = mockStatic(ServletContextRequest.class);
             Fixture fixture = new Fixture()) {
            ServletChannel channel = fixture.bindChannel(contexts);
            int slabs = bytes.length / 1024;
            fixture.pool.failReleaseAt = slabs + 1;
            ProxyCall call = fixture.reply(bytes, splitToBytes());
            fixture.until(() -> assertTrue(call.completion().toCompletableFuture().isDone()));

            assertSame(fixture.pool.releaseFailure, call.completion().toCompletableFuture().join().failure());
            assertEquals(1, fixture.output.writes.get());
            assertEquals(0, fixture.pool.outstanding.get());
            assertEquals(fixture.pool.acquired.get(), fixture.pool.released.get());
            assertEquals(0, fixture.engine.reservedRewriteBytes());
            assertEquals(0, fixture.engine.activeRequests());
            verify(channel).abort(fixture.pool.releaseFailure);
        }
    }

    private static ProxyResponseBodyPolicy.Rewriter splitToBytes() {
        return (metadata, input) -> {
            List<ByteBuffer> parts = new ArrayList<>();
            for (ByteBuffer buffer : input.buffers())
                for (int at = buffer.position(); at < buffer.limit(); at++) parts.add(buffer.slice(at, 1));
            return ProxyResponseBody.of(parts);
        };
    }

    private static byte[] random(int count) { byte[] bytes = new byte[count]; new Random(73).nextBytes(bytes); return bytes; }
    private static byte[] hash(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }

    private static final class Fixture implements AutoCloseable {
        final PolicyTestExchange owner = new PolicyTestExchange();
        final TrackingPool pool = new TrackingPool();
        final Output output = new Output();
        final JettyProxyEngine engine;

        Fixture() throws Exception { this(1024); }

        Fixture(int chunkBytes) throws Exception {
            owner.engine.close();
            when(owner.client.getByteBufferPool()).thenReturn(pool);
            when(owner.outgoing.getOutputStream()).thenReturn(output);
            when(owner.outgoing.isCommitted()).thenAnswer(ignored -> output.committed);
            JettyProxyProperties properties = new JettyProxyProperties(PolicyTestExchange.TARGET.resolve("/"),
                    chunkBytes, 16_384, 1, 1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));
            engine = new JettyProxyEngine(owner.client, properties);
        }

        ProxyCall reply(byte[] bytes, ProxyResponseBodyPolicy.Rewriter rewriter) {
            var plan = ProxyRequestPlan.builder(PolicyTestExchange.TARGET).responseBody((request, response) ->
                    new ProxyResponseBodyPolicy.Selection(Math.max(bytes.length, 4096), Math.max(bytes.length, 4096), rewriter)).build();
            return reply(bytes, plan, HttpFields.EMPTY);
        }

        ProxyCall reply(byte[] bytes, ProxyRequestPlan plan, HttpFields headers) {
            ProxyCall call = engine.forward(owner.incoming, owner.outgoing, plan);
            owner.executor.drain();
            owner.reply(200, bytes, headers);
            owner.executor.drain();
            return call;
        }

        void until(Runnable assertion) {
            await().pollInSameThread().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                owner.executor.drain();
                assertion.run();
            });
        }

        ServletChannel bindChannel(MockedStatic<ServletContextRequest> contexts) {
            ServletContextRequest context = mock(ServletContextRequest.class);
            ServletChannel channel = mock(ServletChannel.class);
            when(context.getServletChannel()).thenReturn(channel);
            contexts.when(() -> ServletContextRequest.getServletContextRequest(owner.incoming)).thenReturn(context);
            return channel;
        }

        @Override public void close() { engine.close(); owner.executor.drain(); owner.close(); }
    }

    private static final class TrackingPool implements ByteBufferPool {
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        final AtomicInteger outstanding = new AtomicInteger();
        final IllegalStateException releaseFailure = new IllegalStateException("Batch release failed");
        volatile int failReleaseAt;
        @Override public RetainableByteBuffer.Mutable acquire(int size, boolean direct) {
            assertTrue(direct);
            int id = acquired.incrementAndGet();
            outstanding.incrementAndGet();
            ByteBuffer buffer = ByteBuffer.allocateDirect(size);
            return RetainableByteBuffer.wrap(buffer, () -> {
                released.incrementAndGet();
                outstanding.decrementAndGet();
                buffer.clear();
                while (buffer.hasRemaining()) buffer.put((byte)0x5A);
                if (id == failReleaseAt) throw releaseFailure;
            });
        }
        @Override public void clear() { }
    }

    private static final class Output extends ServletOutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final AtomicInteger writes = new AtomicInteger();
        WriteListener listener;
        boolean ready = true;
        boolean blockAfterWrite;
        boolean committed;
        byte[] pendingArray;
        @Override public boolean isReady() { return ready; }
        @Override public void setWriteListener(WriteListener listener) { this.listener = listener; }
        @Override public void write(int value) { throw new AssertionError("A bulk write was expected"); }
        @Override public void write(byte[] value, int offset, int length) {
            committed = true;
            pendingArray = value;
            bytes.write(value, offset, length);
            writes.incrementAndGet();
            if (blockAfterWrite) ready = false;
        }
        @Override public void flush() { committed = true; }
    }
}
