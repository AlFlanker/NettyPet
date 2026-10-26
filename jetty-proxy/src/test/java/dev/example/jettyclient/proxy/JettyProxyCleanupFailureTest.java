package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Cleanup faults and a missing client executor must not orphan a Servlet async or admission. */
@Timeout(10)
class JettyProxyCleanupFailureTest {
    private static final URI TARGET = URI.create("http://upstream.example/resource");

    @Test
    void rewriteSlabReleaseFailureDoesNotStrandCompletionOrAdmission() throws Exception {
        HttpClient client = mock(ProxyHttpClient.class);
        ManualExecutor executor = new ManualExecutor();
        Request upstream = mock(Request.class, RETURNS_SELF);
        AtomicReference<Response.Listener> listener = new AtomicReference<>();
        IllegalStateException releaseFailure = new IllegalStateException("Rewrite pool release failed");
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        ByteBufferPool pool = new ByteBufferPool() {
            @Override public RetainableByteBuffer.Mutable acquire(int size, boolean direct) {
                assertTrue(direct);
                acquired.incrementAndGet();
                return RetainableByteBuffer.wrap(ByteBuffer.allocateDirect(size), () -> {
                    released.incrementAndGet();
                    throw releaseFailure;
                });
            }
            @Override public void clear() { }
        };
        when(client.getExecutor()).thenReturn(executor);
        when(client.getByteBufferPool()).thenReturn(pool);
        when(client.newRequest(any(URI.class))).thenReturn(upstream);
        doAnswer(invocation -> {
            listener.set((Response.Listener)invocation.getArgument(0, Response.CompleteListener.class));
            return null;
        }).when(upstream).send(any(Response.CompleteListener.class));

        HttpServletRequest incoming = incoming();
        HttpServletResponse outgoing = mock(HttpServletResponse.class);
        AsyncContext async = mock(AsyncContext.class);
        Output output = new Output();
        when(incoming.startAsync(incoming, outgoing)).thenReturn(async);
        when(outgoing.getOutputStream()).thenReturn(output);
        byte[] body = "body borrowed by the rewriter".getBytes(StandardCharsets.US_ASCII);

        try (JettyProxyEngine engine = new JettyProxyEngine(client, properties())) {
            var plan = ProxyRequestPlan.builder(TARGET).responseBody((request, response) ->
                    new ProxyResponseBodyPolicy.Selection(1024, 1024, (metadata, input) -> input)).build();
            ProxyCall call = engine.forward(incoming, outgoing, plan);
            executor.drain();
            assertNotNull(listener.get());

            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            when(response.getHeaders()).thenReturn(HttpFields.build()
                    .put("Content-Type", "text/plain").put("Content-Length", Integer.toString(body.length)).asImmutable());
            listener.get().onHeaders(response);
            listener.get().onContentSource(response, Content.Source.from(ByteBuffer.wrap(body)));
            listener.get().onSuccess(response);
            listener.get().onComplete(new Result(upstream, response));

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertDoesNotThrow(executor::drain, "Cleanup errors must not escape the serialized owner");
                assertTrue(call.completion().toCompletableFuture().isDone());
            });
            ProxyExchangeResult result = call.completion().toCompletableFuture().join();
            assertEquals(200, result.status(), "The successfully written status cannot be replaced during cleanup");
            assertSame(releaseFailure, result.failure());
            assertFalse(result.success());
            assertEquals(body.length, result.downloadedBytes());
            assertArrayEquals(body, output.bytes.toByteArray());
            assertEquals(1, acquired.get(), "One selected body must occupy exactly one slab here");
            assertEquals(1, released.get(), "A throwing release must not be retried");
            assertEquals(0, engine.activeRequests());
            verify(async, times(1)).complete();

            assertAdmissionRecovered(engine, executor);
            verify(client, times(1)).newRequest(any(URI.class));
            assertEquals(1, released.get());
        } finally {
            executor.drain();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingClientExecutorRejectsBeforeTakingOwnershipAndCanRecover(boolean supplied) {
        HttpClient client = new ProxyHttpClient(new org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP(1));
        assertNull(client.getExecutor(), "Jetty creates its default executor only when the client starts");
        HttpServletRequest incoming = incoming();
        HttpServletResponse outgoing = mock(HttpServletResponse.class);
        AsyncContext async = mock(AsyncContext.class);
        when(incoming.isAsyncStarted()).thenReturn(supplied);
        when(async.getRequest()).thenReturn(incoming);
        when(async.getResponse()).thenReturn(outgoing);

        try (JettyProxyEngine engine = new JettyProxyEngine(client, properties())) {
            ProxyCall call = assertDoesNotThrow(() -> engine.forward(incoming, outgoing, supplied ? async : null,
                    ProxyRequestPlan.builder(TARGET).build()));

            ProxyExchangeResult result = call.completion().toCompletableFuture().join();
            assertEquals(503, result.status());
            assertNotNull(result.failure());
            assertFalse(result.success());
            assertEquals(0, engine.activeRequests());
            verify(incoming, never()).startAsync(any(), any());
            verify(async, times(supplied ? 1 : 0)).complete();
            verify(outgoing).setStatus(503);

            // Installing an executor makes a local, no-network exchange possible;
            // this also detects a permit lost by the readiness rejection.
            ManualExecutor executor = new ManualExecutor();
            client.setExecutor(executor);
            assertAdmissionRecovered(engine, executor);
        }
    }

    private static void assertAdmissionRecovered(JettyProxyEngine engine, ManualExecutor executor) {
        HttpServletRequest next = incoming();
        HttpServletResponse response = mock(HttpServletResponse.class);
        AsyncContext async = mock(AsyncContext.class);
        when(next.getMethod()).thenReturn("OPTIONS");
        when(next.getHeaderNames()).thenReturn(Collections.enumeration(List.of("Max-Forwards")));
        when(next.getHeaders(anyString())).thenAnswer(invocation -> Collections.enumeration(
                "Max-Forwards".equals(invocation.getArgument(0)) ? List.of("0") : List.of()));
        when(next.startAsync(next, response)).thenReturn(async);

        ProxyCall call = engine.forward(next, response, ProxyRequestPlan.builder(TARGET).build());
        assertDoesNotThrow(executor::drain);
        assertEquals(200, call.completion().toCompletableFuture().join().status(),
                "The sole admission permit must be available after cleanup/rejection");
        assertEquals(0, engine.activeRequests());
        verify(async).complete();
    }

    private static HttpServletRequest incoming() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.isAsyncSupported()).thenReturn(true);
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getHeaders(anyString())).thenAnswer(ignored -> Collections.emptyEnumeration());
        return request;
    }

    private static JettyProxyProperties properties() {
        return new JettyProxyProperties(URI.create("http://127.0.0.1:9000"),
                1024, 16_384, 1, 1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));
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
                assertTrue(++count < 10_000, "Serialized processing must converge");
                task.run();
            }
        }
    }
}
