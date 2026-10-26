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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Contracts used by the legacy Filter/executor boundary, without an actual upstream socket. */
@Timeout(10)
class JettyProxyPlanLifecycleTest {
    private static final URI TARGET = URI.create("http://upstream.example/a%20b?x=1&x=2");

    @Test
    void suppliedAsyncIsNotRestartedAndClientFailureCompletesItExactlyOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prestarted();
            IOException failure = new IOException("Client handoff failed");
            when(fixture.client.newRequest(any(URI.class))).thenThrow(new IllegalStateException(failure));

            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing, fixture.async,
                    ProxyRequestPlan.builder(TARGET).build());
            assertFalse(call.completion().toCompletableFuture().isDone());
            fixture.executor.drain();

            ProxyExchangeResult result = call.completion().toCompletableFuture().join();
            assertEquals(502, result.status());
            assertFalse(result.success());
            assertNotNull(result.failure());
            assertEquals(0, result.uploadedBytes());
            assertEquals(0, result.downloadedBytes());
            assertFalse(result.elapsed().isNegative());
            verify(fixture.incoming, never()).startAsync(any(), any());
            verify(fixture.async, times(1)).complete();
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void suppliedAsyncIsCompletedOnShutdownRejectionBeforeAdmission() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prestarted();
            fixture.engine.close();

            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing, fixture.async,
                    ProxyRequestPlan.builder(TARGET).build());

            assertEquals(503, call.completion().toCompletableFuture().join().status());
            verify(fixture.async, times(1)).complete();
            verify(fixture.incoming, never()).startAsync(any(), any());
            verifyNoInteractions(fixture.client);
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void suppliedAsyncIsCompletedWhenTargetCannotBeProxied() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prestarted();

            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing, fixture.async,
                    ProxyRequestPlan.builder(URI.create("ftp://upstream.example/resource")).build());

            assertEquals(400, call.completion().toCompletableFuture().join().status());
            verify(fixture.async, times(1)).complete();
            verify(fixture.incoming, never()).startAsync(any(), any());
            verifyNoInteractions(fixture.client);
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void callerMustSupplyTheSameRequestWrapperThatStartedAsync() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prestarted();
            when(fixture.async.getRequest()).thenReturn(mock(HttpServletRequest.class));

            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing, fixture.async,
                    ProxyRequestPlan.builder(TARGET).build());

            assertEquals(500, call.completion().toCompletableFuture().join().status());
            verify(fixture.incoming, never()).startAsync(any(), any());
            verify(fixture.async, never()).complete();
            verifyNoInteractions(fixture.client);
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void callerMustSupplyTheSameResponseWrapperThatStartedAsync() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prestarted();
            when(fixture.async.getResponse()).thenReturn(mock(HttpServletResponse.class));

            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing, fixture.async,
                    ProxyRequestPlan.builder(TARGET).build());

            assertEquals(500, call.completion().toCompletableFuture().join().status());
            verify(fixture.incoming, never()).startAsync(any(), any());
            verify(fixture.async, never()).complete();
            verifyNoInteractions(fixture.client);
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void aProtocolRejectionCannotCompleteAnUnrelatedSuppliedAsyncContext() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prestarted();
            when(fixture.async.getRequest()).thenReturn(mock(HttpServletRequest.class));
            when(fixture.incoming.getHeader("Expect")).thenReturn("100-continue");

            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing, fixture.async,
                    ProxyRequestPlan.builder(TARGET).build());

            assertEquals(500, call.completion().toCompletableFuture().join().status(),
                    "Async ownership must be checked before an ordinary HTTP rejection can complete it");
            verify(fixture.async, never()).complete();
            verify(fixture.incoming, never()).startAsync(any(), any());
            verifyNoInteractions(fixture.client);
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void externallyCompletingOrCancellingACompletionProjectionCannotPublishTheActualOutcome() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(TARGET).build());
            ProxyExchangeResult invented = new ProxyExchangeResult(418, null, 0, 0, Duration.ZERO);
            assertTrue(call.completion().toCompletableFuture().complete(invented));
            assertTrue(call.completion().toCompletableFuture().cancel(false));
            assertFalse(call.completion().toCompletableFuture().isDone(),
                    "CompletionStage consumers must not mutate the exchange's own completion future");

            fixture.executor.drain();
            assertEquals(1, fixture.engine.activeRequests());
            verify(fixture.upstream, never()).abort(any(Throwable.class));
            fixture.reply(200, "real result".getBytes(StandardCharsets.US_ASCII));
            fixture.executor.drain();

            ProxyExchangeResult actual = call.completion().toCompletableFuture().join();
            assertEquals(200, actual.status());
            assertTrue(actual.success());
            assertNotSame(invented, actual);
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void cancellationCompletesOnlyAfterUpstreamCleanupAndPreservesFirstCause() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(TARGET).build());
            AtomicInteger observed = new AtomicInteger();
            AtomicInteger activeAtCompletion = new AtomicInteger(-1);
            call.completion().thenAccept(result -> {
                activeAtCompletion.set(fixture.engine.activeRequests());
                observed.incrementAndGet();
            });
            fixture.executor.drain();
            assertNotNull(fixture.listener.get());

            IOException first = new IOException("Cancelled by outer command");
            call.cancel(first);
            call.cancel(new IOException("Duplicate cancellation"));
            fixture.executor.drain();

            assertFalse(call.completion().toCompletableFuture().isDone(),
                    "Aborting a Request does not itself prove that its final callback has returned");
            assertEquals(1, fixture.engine.activeRequests());
            verify(fixture.async, times(1)).complete();
            verify(fixture.upstream, times(1)).abort(first);

            fixture.listener.get().onComplete(new Result(fixture.upstream, first, null, null));
            fixture.executor.drain();
            ProxyExchangeResult result = call.completion().toCompletableFuture().join();
            assertFalse(result.success());
            assertEquals(503, result.status());
            assertSame(first, result.failure());
            assertEquals(1, observed.get());
            assertEquals(0, activeAtCompletion.get());
            assertEquals(0, fixture.engine.activeRequests());

            call.cancel(new IOException("Cancellation after cleanup"));
            fixture.listener.get().onComplete(new Result(fixture.upstream, first, null, null));
            fixture.executor.drain();
            assertSame(result, call.completion().toCompletableFuture().join());
            assertEquals(1, observed.get());
            verify(fixture.async, times(1)).complete();
        }
    }

    @Test
    void completionObserversSeeCleanupAndTheirFailureCannotChangeTheResult() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(TARGET).build());
            AtomicInteger activeAtCompletion = new AtomicInteger(-1);
            IllegalStateException observerFailure = new IllegalStateException("Metrics subscriber failed");
            CompletableFuture<Void> dependent = call.completion().thenAccept(result -> {
                activeAtCompletion.set(fixture.engine.activeRequests());
                throw observerFailure;
            }).toCompletableFuture();
            fixture.executor.drain();

            byte[] bytes = "proxied response".getBytes(StandardCharsets.US_ASCII);
            fixture.reply(200, bytes);
            assertDoesNotThrow(fixture.executor::drain);

            ProxyExchangeResult result = call.completion().toCompletableFuture().join();
            assertTrue(result.success());
            assertEquals(200, result.status());
            assertNull(result.failure());
            assertEquals(bytes.length, result.downloadedBytes());
            assertArrayEquals(bytes, fixture.output.bytes.toByteArray());
            assertEquals(0, activeAtCompletion.get());
            assertSame(observerFailure, assertThrows(CompletionException.class, dependent::join).getCause());

            // A failing observer must not strand admission or poison later serialized drains.
            ProxyCall next = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(TARGET).build());
            fixture.executor.drain();
            assertEquals(1, fixture.engine.activeRequests());
            fixture.reply(204, new byte[0]);
            fixture.executor.drain();
            assertTrue(next.completion().toCompletableFuture().join().success());
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void perRequestTimeoutCancelsAStalledExchangeWithoutWaitingForTheGlobalDeadline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.upstream.abort(any(Throwable.class))).thenAnswer(invocation -> {
                Throwable failure = invocation.getArgument(0);
                fixture.listener.get().onComplete(new Result(fixture.upstream, failure, null, null));
                return CompletableFuture.completedFuture(true);
            });
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(TARGET).timeout(Duration.ofMillis(50)).build());
            fixture.executor.drain();

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                fixture.executor.drain();
                assertTrue(call.completion().toCompletableFuture().isDone());
            });
            ProxyExchangeResult result = call.completion().toCompletableFuture().join();
            assertEquals(504, result.status());
            assertFalse(result.success());
            assertInstanceOf(java.util.concurrent.TimeoutException.class, result.failure());
            verify(fixture.async, times(1)).complete();
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void perRequestHeadersRunAfterGlobalHooksWithoutRestoringConnectionNominatedHeaders() throws Exception {
        ProxyHeaderCustomizer global = new ProxyHeaderCustomizer() {
            @Override public void customizeRequest(ProxyRequestMetadata request, ProxyHeaderEditor headers) {
                headers.set("X-Order", "global");
            }
        };
        try (Fixture fixture = new Fixture(List.of(global))) {
            fixture.headers(Map.of("Connection", List.of("X-Deleted"), "X-Deleted", List.of("original")));
            ProxyHeaderCustomizer perRequest = new ProxyHeaderCustomizer() {
                @Override public void customizeRequest(ProxyRequestMetadata request, ProxyHeaderEditor headers) {
                    headers.set("X-Order", headers.values("X-Order").getFirst() + ",local");
                    headers.set("X-Deleted", "must remain excluded");
                }
            };

            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(TARGET).customizer(perRequest).build());
            fixture.executor.drain();
            assertEquals("global,local", fixture.sentHeaders.get().get("X-Order"));
            assertNull(fixture.sentHeaders.get().get("X-Deleted"));
            assertNull(fixture.sentHeaders.get().get("Connection"));

            fixture.reply(204, new byte[0]);
            fixture.executor.drain();
            assertTrue(call.completion().toCompletableFuture().join().success());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ManualExecutor executor = new ManualExecutor();
        final HttpClient client = mock(HttpClient.class);
        final Request upstream = mock(Request.class, RETURNS_SELF);
        final HttpServletRequest incoming = mock(HttpServletRequest.class);
        final HttpServletResponse outgoing = mock(HttpServletResponse.class);
        final AsyncContext async = mock(AsyncContext.class);
        final Output output = new Output();
        final AtomicReference<Response.Listener> listener = new AtomicReference<>();
        final AtomicReference<HttpFields> sentHeaders = new AtomicReference<>();
        final JettyProxyEngine engine;

        Fixture() throws Exception { this(List.of()); }

        Fixture(List<ProxyHeaderCustomizer> customizers) throws Exception {
            when(client.getExecutor()).thenReturn(executor);
            when(client.getByteBufferPool()).thenReturn(ByteBufferPool.NON_POOLING);
            when(client.newRequest(any(URI.class))).thenReturn(upstream);
            when(upstream.abort(any(Throwable.class))).thenReturn(CompletableFuture.completedFuture(true));
            doAnswer(invocation -> {
                listener.set((Response.Listener)invocation.getArgument(0, Response.CompleteListener.class));
                return null;
            }).when(upstream).send(any(Response.CompleteListener.class));
            doAnswer(invocation -> {
                HttpFields.Mutable fields = HttpFields.build();
                Consumer<HttpFields.Mutable> update = invocation.getArgument(0);
                update.accept(fields);
                sentHeaders.set(fields.asImmutable());
                return upstream;
            }).when(upstream).headers(any());
            when(incoming.getMethod()).thenReturn("GET");
            when(incoming.isAsyncSupported()).thenReturn(true);
            when(incoming.getContentLengthLong()).thenReturn(-1L);
            headers(Map.of());
            when(incoming.startAsync(incoming, outgoing)).thenReturn(async);
            when(async.getRequest()).thenReturn(incoming);
            when(async.getResponse()).thenReturn(outgoing);
            when(outgoing.getOutputStream()).thenReturn(output);
            JettyProxyProperties properties = new JettyProxyProperties(URI.create("http://127.0.0.1:9000"),
                    1024, 16_384, 1, 1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));
            engine = new JettyProxyEngine(client, properties, customizers);
        }

        void prestarted() { when(incoming.isAsyncStarted()).thenReturn(true); }

        void headers(Map<String, List<String>> values) {
            when(incoming.getHeaderNames()).thenAnswer(ignored -> Collections.enumeration(values.keySet()));
            when(incoming.getHeader(anyString())).thenAnswer(invocation -> values.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(invocation.getArgument(0)))
                    .findFirst().map(entry -> entry.getValue().getFirst()).orElse(null));
            when(incoming.getHeaders(anyString())).thenAnswer(invocation -> Collections.enumeration(values.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(invocation.getArgument(0)))
                    .findFirst().map(Map.Entry::getValue).orElse(List.of())));
        }

        void reply(int status, byte[] bytes) {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(status);
            when(response.getHeaders()).thenReturn(HttpFields.build()
                    .put("Content-Length", Integer.toString(bytes.length)).asImmutable());
            listener.get().onHeaders(response);
            listener.get().onContentSource(response, Content.Source.from(ByteBuffer.wrap(bytes)));
            listener.get().onSuccess(response);
            listener.get().onComplete(new Result(upstream, response));
        }

        @Override public void close() {
            engine.close();
            executor.drain();
        }
    }

    private static final class Output extends ServletOutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public boolean isReady() { return true; }
        @Override public void setWriteListener(WriteListener listener) { }
        @Override public void write(int value) { bytes.write(value); }
        @Override public void write(byte[] value, int offset, int length) { bytes.write(value, offset, length); }
    }

    private static final class ManualExecutor implements Executor {
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void drain() {
            int count = 0;
            Runnable task;
            while ((task = tasks.poll()) != null) {
                assertTrue(++count < 10_000, "Serialized event processing must converge");
                task.run();
            }
        }
    }
}
