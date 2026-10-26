package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** CPU/queue and buffered-payload admission are independent of ordinary streaming. */
@Timeout(15)
class JettyProxyRewriteBudgetTest {
    private static final int CHUNK_BYTES = 1024;
    private static final long INPUT_LIMIT = 1024;
    private static final long OUTPUT_LIMIT = 1024;
    private static final long RESERVATION = INPUT_LIMIT + OUTPUT_LIMIT + CHUNK_BYTES;
    private static final byte[] BODY = "bounded rewrite body".getBytes(StandardCharsets.US_ASCII);

    @Test
    void defaultsAndValidationAreExplicitAndOldConstructorsUseThoseDefaults() {
        ProxyRewriteOptions defaults = ProxyRewriteOptions.defaults();
        assertEquals(new ProxyRewriteOptions(2, 8, 128L * 1024 * 1024), defaults);
        assertThrows(IllegalArgumentException.class, () -> new ProxyRewriteOptions(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProxyRewriteOptions(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProxyRewriteOptions(1, 1, 0));
        try (JettyProxyEngine engine = new JettyProxyEngine(mock(ProxyHttpClient.class), properties())) {
            assertEquals(defaults, engine.rewriteOptions());
            assertEquals(0, engine.reservedRewriteBytes());
            assertEquals(defaults.maxBufferedBytes(), engine.availableRewriteBytes());
        }
    }

    @Test
    void exhaustedBudgetRejectsBeforeCollectionAndRunningCancellationKeepsItsReservationUntilWorkerReturns()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(new ProxyRewriteOptions(1, 1, RESERVATION))) {
            try {
                Exchange running = fixture.exchange("running");
                ProxyCall runningCall = running.startAndReply(fixture.engine, rewrite(running.target, (metadata, body) -> {
                    entered.countDown();
                    assertTrue(release.await(8, TimeUnit.SECONDS), "The test must release the worker");
                    return body;
                }));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(RESERVATION, fixture.engine.reservedRewriteBytes());
                assertEquals(0, fixture.engine.availableRewriteBytes());
                assertEquals(1, fixture.acquisitions.get());

                AtomicBoolean rejectedRewriterCalled = new AtomicBoolean();
                Exchange rejected = fixture.exchange("budget-rejected");
                ProxyCall rejectedCall = rejected.startAndReply(fixture.engine, rewrite(rejected.target, (metadata, body) -> {
                    rejectedRewriterCalled.set(true);
                    return body;
                }));
                ProxyExchangeResult rejection = completed(rejectedCall);
                assertEquals(503, rejection.status());
                assertFalse(rejection.success());
                assertFalse(rejectedRewriterCalled.get());
                assertEquals(1, fixture.acquisitions.get(), "Budget rejection must not allocate a collector slab");
                assertEquals(RESERVATION, fixture.engine.reservedRewriteBytes());

                Exchange stream = fixture.exchange("ordinary-stream");
                ProxyExchangeResult streamed = completed(stream.startAndReply(fixture.engine,
                        ProxyRequestPlan.builder(stream.target).build()));
                assertTrue(streamed.success(), "A full rewrite budget must not reject streaming requests");
                assertArrayEquals(BODY, stream.output.bytes.toByteArray());
                assertEquals(1, fixture.acquisitions.get());

                IOException cancelled = new IOException("Running rewrite no longer needed");
                runningCall.cancel(cancelled);
                assertFalse(runningCall.completion().toCompletableFuture().isDone(),
                        "A running worker still borrows its collector input");
                assertEquals(RESERVATION, fixture.engine.reservedRewriteBytes());
                assertEquals(0, fixture.releases.get());
                assertEquals(1, fixture.engine.activeRequests());

                release.countDown();
                ProxyExchangeResult cancellation = runningCall.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(503, cancellation.status());
                assertSame(cancelled, cancellation.failure());
                assertEquals(0, fixture.engine.reservedRewriteBytes());
                assertEquals(RESERVATION, fixture.engine.availableRewriteBytes());
                assertEquals(1, fixture.releases.get());
                assertEquals(0, fixture.engine.activeRequests());

                Exchange recovered = fixture.exchange("recovered");
                ProxyExchangeResult recovery = recovered.startAndReply(fixture.engine,
                        rewrite(recovered.target, (metadata, body) -> body)).completion()
                        .toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertTrue(recovery.success());
                assertArrayEquals(BODY, recovered.output.bytes.toByteArray());
                assertEquals(0, fixture.engine.reservedRewriteBytes());
                assertEquals(fixture.acquisitions.get(), fixture.releases.get());
            } finally { release.countDown(); }
        }
    }

    @Test
    void overflowingReservationIsRejectedWithoutAllocatingOrCallingTheRewriter() throws Exception {
        try (Fixture fixture = new Fixture(new ProxyRewriteOptions(1, 1, Long.MAX_VALUE))) {
            AtomicBoolean called = new AtomicBoolean();
            Exchange exchange = fixture.exchange("overflow");
            ProxyRequestPlan plan = ProxyRequestPlan.builder(exchange.target).responseBody((request, response) ->
                    new ProxyResponseBodyPolicy.Selection(Long.MAX_VALUE, 1, (metadata, body) -> {
                        called.set(true);
                        return body;
                    })).build();

            ProxyExchangeResult outcome = completed(exchange.startAndReply(fixture.engine, plan));

            assertEquals(503, outcome.status());
            assertFalse(outcome.success());
            assertFalse(called.get());
            assertEquals(0, fixture.acquisitions.get());
            assertEquals(0, fixture.engine.reservedRewriteBytes());
            assertEquals(Long.MAX_VALUE, fixture.engine.availableRewriteBytes());
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void independentWorkerQueueRejectsOnlyTheOverflowingRewriteAndReturnsItsReservation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedRewriterCalled = new AtomicBoolean();
        AtomicBoolean rejectedRewriterCalled = new AtomicBoolean();
        ProxyRewriteOptions options = new ProxyRewriteOptions(1, 1, RESERVATION * 4);
        try (Fixture fixture = new Fixture(options)) {
            try {
                assertEquals(options, fixture.engine.rewriteOptions());
                Exchange running = fixture.exchange("queue-running");
                ProxyCall runningCall = running.startAndReply(fixture.engine, rewrite(running.target, (metadata, body) -> {
                    entered.countDown();
                    assertTrue(release.await(8, TimeUnit.SECONDS));
                    return body;
                }));
                assertTrue(entered.await(3, TimeUnit.SECONDS));

                Exchange queued = fixture.exchange("queue-waiting");
                ProxyCall queuedCall = queued.startAndReply(fixture.engine, rewrite(queued.target, (metadata, body) -> {
                    queuedRewriterCalled.set(true);
                    return body;
                }));
                assertFalse(queuedRewriterCalled.get());
                assertFalse(queuedCall.completion().toCompletableFuture().isDone());
                assertEquals(RESERVATION * 2, fixture.engine.reservedRewriteBytes());

                Exchange rejected = fixture.exchange("queue-overflow");
                ProxyExchangeResult overflow = completed(rejected.startAndReply(fixture.engine,
                        rewrite(rejected.target, (metadata, body) -> {
                            rejectedRewriterCalled.set(true);
                            return body;
                        })));
                assertEquals(503, overflow.status());
                assertFalse(rejectedRewriterCalled.get());
                assertEquals(RESERVATION * 2, fixture.engine.reservedRewriteBytes(),
                        "A rejected executor job cannot retain its payload reservation");
                assertEquals(3, fixture.acquisitions.get());
                assertEquals(1, fixture.releases.get());
                assertEquals(2, fixture.engine.activeRequests());

                Exchange stream = fixture.exchange("queue-stream");
                assertTrue(completed(stream.startAndReply(fixture.engine,
                        ProxyRequestPlan.builder(stream.target).build())).success());
                assertArrayEquals(BODY, stream.output.bytes.toByteArray());

                release.countDown();
                assertTrue(runningCall.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).success());
                assertTrue(queuedCall.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).success());
                assertTrue(queuedRewriterCalled.get());
                assertEquals(0, fixture.engine.reservedRewriteBytes());
                assertEquals(options.maxBufferedBytes(), fixture.engine.availableRewriteBytes());
                assertEquals(fixture.acquisitions.get(), fixture.releases.get());
                assertEquals(0, fixture.engine.activeRequests());
            } finally { release.countDown(); }
        }
    }

    private static ProxyRequestPlan rewrite(URI target, ProxyResponseBodyPolicy.Rewriter rewriter) {
        return ProxyRequestPlan.builder(target).responseBody((request, response) ->
                new ProxyResponseBodyPolicy.Selection(INPUT_LIMIT, OUTPUT_LIMIT, rewriter)).build();
    }

    private static ProxyExchangeResult completed(ProxyCall call) {
        assertTrue(call.completion().toCompletableFuture().isDone(), "This path must complete without a CPU worker");
        return call.completion().toCompletableFuture().join();
    }

    private static JettyProxyProperties properties() {
        return new JettyProxyProperties(URI.create("http://upstream.example"), CHUNK_BYTES, 16_384,
                4, 4, 4, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    private static final class Fixture implements AutoCloseable {
        final HttpClient client = mock(ProxyHttpClient.class);
        final AtomicInteger acquisitions = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final JettyProxyEngine engine;

        Fixture(ProxyRewriteOptions options) {
            when(client.getExecutor()).thenReturn((Executor)Runnable::run);
            when(client.getByteBufferPool()).thenReturn(new ByteBufferPool() {
                @Override public RetainableByteBuffer.Mutable acquire(int size, boolean direct) {
                    assertTrue(direct);
                    acquisitions.incrementAndGet();
                    return RetainableByteBuffer.wrap(ByteBuffer.allocateDirect(size), releases::incrementAndGet);
                }
                @Override public void clear() { }
            });
            engine = new JettyProxyEngine(client, properties(), List.of(), options);
        }

        Exchange exchange(String path) throws Exception { return new Exchange(client, path); }

        @Override public void close() {
            engine.close();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, engine.activeRequests()));
        }
    }

    private static final class Exchange {
        final URI target;
        final HttpServletRequest incoming = mock(HttpServletRequest.class);
        final HttpServletResponse outgoing = mock(HttpServletResponse.class);
        final Request upstream = mock(Request.class, RETURNS_SELF);
        final AtomicReference<Response.Listener> listener = new AtomicReference<>();
        final Output output = new Output();

        Exchange(HttpClient client, String path) throws Exception {
            target = URI.create("http://upstream.example/" + path);
            when(client.newRequest(target)).thenReturn(upstream);
            when(upstream.abort(any(Throwable.class))).thenAnswer(invocation -> {
                if (listener.get() != null)
                    listener.get().onComplete(new Result(upstream, invocation.getArgument(0), null, null));
                return CompletableFuture.completedFuture(true);
            });
            doAnswer(invocation -> {
                listener.set((Response.Listener)invocation.getArgument(0, Response.CompleteListener.class));
                return null;
            }).when(upstream).send(any(Response.CompleteListener.class));
            when(incoming.getMethod()).thenReturn("GET");
            when(incoming.isAsyncSupported()).thenReturn(true);
            when(incoming.getContentLengthLong()).thenReturn(-1L);
            when(incoming.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
            when(incoming.getHeaders(anyString())).thenAnswer(ignored -> Collections.emptyEnumeration());
            when(incoming.startAsync(incoming, outgoing)).thenReturn(mock(AsyncContext.class));
            when(outgoing.getHeaderNames()).thenReturn(List.of());
            when(outgoing.getOutputStream()).thenReturn(output);
        }

        ProxyCall startAndReply(JettyProxyEngine engine, ProxyRequestPlan plan) {
            ProxyCall call = engine.forward(incoming, outgoing, plan);
            assertNotNull(listener.get(), "The request must reach the outbound client before response selection");
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            when(response.getHeaders()).thenReturn(HttpFields.build()
                    .put("Content-Length", Integer.toString(BODY.length)).asImmutable());
            listener.get().onHeaders(response);
            listener.get().onContentSource(response, Content.Source.from(ByteBuffer.wrap(BODY)));
            listener.get().onSuccess(response);
            listener.get().onComplete(new Result(upstream, response));
            return call;
        }
    }

    private static final class Output extends ServletOutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public boolean isReady() { return true; }
        @Override public void setWriteListener(WriteListener listener) { }
        @Override public void write(int value) { bytes.write(value); }
        @Override public void write(byte[] buffer, int offset, int length) { bytes.write(buffer, offset, length); }
    }
}
