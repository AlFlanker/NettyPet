package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Async application I/O must not consume the body, commit headers or escape the exchange owner. */
@Timeout(10)
class JettyProxyPolicyStagesTest {
    @Test
    void requestFutureDefersNetworkAndBodyAndItsPatchCannotRestoreConnectionNominations() throws Exception {
        CompletableFuture<ProxyHeaderPatch> stage = new CompletableFuture<>();
        AtomicInteger global = new AtomicInteger();
        AtomicInteger local = new AtomicInteger();
        ProxyHeaderCustomizer globalHook = new ProxyHeaderCustomizer() {
            @Override public void customizeRequest(ProxyRequestMetadata metadata, ProxyHeaderEditor headers) {
                global.incrementAndGet();
                headers.set("X-Order", "global");
            }
        };
        try (PolicyTestExchange fixture = new PolicyTestExchange(List.of(globalHook))) {
            when(fixture.incoming.getMethod()).thenReturn("POST");
            when(fixture.incoming.getContentLengthLong()).thenReturn(0L);
            ServletInputStream input = mock(ServletInputStream.class);
            when(input.isFinished()).thenReturn(true);
            when(fixture.incoming.getInputStream()).thenReturn(input);
            fixture.requestHeaders(Map.of("Connection", List.of("X-Secret"), "X-Secret", List.of("original")));
            ProxyRequestPlan plan = ProxyRequestPlan.builder(PolicyTestExchange.TARGET)
                    .customizer(new ProxyHeaderCustomizer() {
                        @Override public void customizeRequest(ProxyRequestMetadata metadata, ProxyHeaderEditor headers) {
                            local.incrementAndGet();
                            headers.set("X-Order", headers.values("X-Order").getFirst() + ",local");
                        }
                    }).requestHeadersAsync(metadata -> stage).build();
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing, plan);
            fixture.executor.drain();

            verify(fixture.incoming).startAsync(fixture.incoming, fixture.outgoing);
            verify(fixture.async).setTimeout(0);
            verify(fixture.client, never()).newRequest(any(URI.class));
            verify(fixture.incoming, never()).getInputStream();
            assertFalse(call.completion().toCompletableFuture().isDone());

            stage.complete(ProxyHeaderPatch.builder().set("X-Order", "async")
                    .set("X-Secret", "must remain excluded").add("X-Prepared", "ready").build());
            fixture.executor.drain();
            assertEquals(1, global.get());
            assertEquals(1, local.get());
            assertEquals("async", fixture.sentHeaders.get().get("X-Order"));
            assertEquals("ready", fixture.sentHeaders.get().get("X-Prepared"));
            assertNull(fixture.sentHeaders.get().get("X-Secret"));
            assertNull(fixture.sentHeaders.get().get("Connection"));
            verify(fixture.incoming).getInputStream();

            fixture.reply(204, new byte[0], HttpFields.EMPTY);
            fixture.executor.drain();
            assertTrue(call.completion().toCompletableFuture().join().success());
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void responseFutureDefersCommitAndContentDemandAndAppliesHooksExactlyOnce() throws Exception {
        CompletableFuture<ProxyHeaderPatch> stage = new CompletableFuture<>();
        AtomicInteger hooks = new AtomicInteger();
        try (PolicyTestExchange fixture = new PolicyTestExchange()) {
            ProxyRequestPlan plan = ProxyRequestPlan.builder(PolicyTestExchange.TARGET)
                    .customizer(new ProxyHeaderCustomizer() {
                        @Override public void customizeResponse(ProxyRequestMetadata metadata, int status,
                                ProxyHeaderEditor headers) {
                            hooks.incrementAndGet();
                            headers.set("X-Response-Order", "hook");
                        }
                    }).responseHeadersAsync((request, response) -> stage).build();
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing, plan);
            fixture.executor.drain();
            byte[] body = "response body".getBytes(StandardCharsets.UTF_8);
            Response response = fixture.response(200, body.length, HttpFields.build()
                    .put("Connection", "X-Secret").put("X-Secret", "origin").asImmutable());
            Content.Source source = mock(Content.Source.class);
            when(source.read()).thenReturn(Content.Chunk.from(ByteBuffer.wrap(body), true));
            fixture.listener.get().onHeaders(response);
            fixture.listener.get().onContentSource(response, source);
            fixture.executor.drain();
            assertFalse(fixture.output.committed.get());
            verify(fixture.outgoing, never()).setStatus(anyInt());
            verify(source, never()).read();

            stage.complete(ProxyHeaderPatch.builder().set("X-Response-Order", "async")
                    .set("X-Secret", "must not be restored").add("Set-Cookie", "prepared=1; Path=/").build());
            fixture.executor.drain();
            fixture.listener.get().onSuccess(response);
            fixture.listener.get().onComplete(new Result(fixture.upstream, response));
            fixture.executor.drain();

            assertTrue(call.completion().toCompletableFuture().join().success());
            assertEquals(1, hooks.get());
            assertEquals(List.of("async"), fixture.header("X-Response-Order"));
            assertTrue(fixture.header("X-Secret").isEmpty());
            assertEquals(List.of("prepared=1; Path=/"), fixture.header("Set-Cookie"));
            assertArrayEquals(body, fixture.output.bytes.toByteArray());
        }
    }

    @Test
    void requestStageTimeoutReleasesAdmissionAndIgnoresItsLateResult() throws Exception {
        CompletableFuture<ProxyHeaderPatch> stage = new CompletableFuture<>();
        AtomicInteger entered = new AtomicInteger();
        try (PolicyTestExchange fixture = new PolicyTestExchange()) {
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(PolicyTestExchange.TARGET).timeout(Duration.ofMillis(180))
                            .requestHeadersAsync(metadata -> { entered.incrementAndGet(); return stage; }).build());
            fixture.executor.drain();
            assertEquals(1, entered.get(), "The timeout must occur while the application stage is pending");
            fixture.awaitDone(call);
            assertEquals(504, call.completion().toCompletableFuture().join().status());
            assertEquals(0, fixture.engine.activeRequests());
            verify(fixture.client, never()).newRequest(any(URI.class));
            verify(fixture.async, times(1)).complete();

            stage.complete(ProxyHeaderPatch.builder().set("X-Late", "must not apply").build());
            fixture.executor.drain();
            assertTrue(fixture.header("X-Late").isEmpty());
            verify(fixture.client, never()).newRequest(any(URI.class));
            fixture.assertAdmissionRecovered();
        }
    }

    @Test
    void responseStageTimeoutDoesNotReadBodyOrApplyALatePatch() throws Exception {
        CompletableFuture<ProxyHeaderPatch> stage = new CompletableFuture<>();
        AtomicInteger entered = new AtomicInteger();
        try (PolicyTestExchange fixture = new PolicyTestExchange()) {
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(PolicyTestExchange.TARGET).timeout(Duration.ofMillis(180))
                            .responseHeadersAsync((request, response) -> { entered.incrementAndGet(); return stage; }).build());
            fixture.executor.drain();
            Response response = fixture.response(200, 4, HttpFields.EMPTY);
            Content.Source source = mock(Content.Source.class);
            fixture.listener.get().onHeaders(response);
            fixture.listener.get().onContentSource(response, source);
            fixture.executor.drain();
            assertEquals(1, entered.get(), "The response policy must be pending before the deadline fires");
            fixture.awaitDone(call);
            assertEquals(504, call.completion().toCompletableFuture().join().status());
            verify(source, never()).read();
            assertEquals(0, fixture.engine.activeRequests());

            stage.complete(ProxyHeaderPatch.builder().set("X-Late", "must not apply").build());
            fixture.executor.drain();
            assertTrue(fixture.header("X-Late").isEmpty());
            verify(source, never()).read();
            verify(fixture.async, times(1)).complete();
        }
    }

    @ParameterizedTest
    @EnumSource(StageAt.class)
    void failedApplicationStageProducesLocal500AndDoesNotStrandTheExchange(StageAt at) throws Exception {
        CompletableFuture<ProxyHeaderPatch> stage = new CompletableFuture<>();
        try (PolicyTestExchange fixture = new PolicyTestExchange()) {
            ProxyRequestPlan.Builder builder = ProxyRequestPlan.builder(PolicyTestExchange.TARGET);
            if (at == StageAt.REQUEST) builder.requestHeadersAsync(metadata -> stage);
            else builder.responseHeadersAsync((request, response) -> stage);
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing, builder.build());
            fixture.executor.drain();
            if (at == StageAt.RESPONSE) {
                fixture.listener.get().onHeaders(fixture.response(200, 0, HttpFields.EMPTY));
                fixture.executor.drain();
            }
            stage.completeExceptionally(new IllegalStateException("Application storage failed"));
            fixture.executor.drain();
            assertEquals(500, call.completion().toCompletableFuture().join().status());
            assertFalse(call.completion().toCompletableFuture().join().success());
            assertEquals(0, fixture.engine.activeRequests());
            verify(fixture.async, times(1)).complete();
            if (at == StageAt.REQUEST) verify(fixture.client, never()).newRequest(any(URI.class));
        }
    }

    private enum StageAt { REQUEST, RESPONSE }
}

/** Shared only by the two policy contract suites; callbacks are drained explicitly by the test owner. */
final class PolicyTestExchange implements AutoCloseable {
    static final URI TARGET = URI.create("http://upstream.example/policy?x=1&x=2");
    final ManualExecutor executor = new ManualExecutor();
    final HttpClient client = mock(ProxyHttpClient.class);
    final Request upstream = mock(Request.class, RETURNS_SELF);
    final HttpServletRequest incoming = mock(HttpServletRequest.class);
    final HttpServletResponse outgoing = mock(HttpServletResponse.class);
    final AsyncContext async = mock(AsyncContext.class);
    final Output output = new Output();
    final AtomicReference<Response.Listener> listener = new AtomicReference<>();
    final AtomicReference<HttpFields> sentHeaders = new AtomicReference<>();
    final AtomicInteger status = new AtomicInteger();
    final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    final JettyProxyEngine engine;

    PolicyTestExchange() throws Exception { this(List.of()); }

    PolicyTestExchange(List<ProxyHeaderCustomizer> customizers) throws Exception {
        when(client.getExecutor()).thenReturn(executor);
        when(client.getByteBufferPool()).thenReturn(ByteBufferPool.NON_POOLING);
        when(client.newRequest(any(URI.class))).thenReturn(upstream);
        when(upstream.abort(any(Throwable.class))).thenAnswer(invocation -> {
            if (listener.get() != null)
                listener.get().onComplete(new Result(upstream, invocation.getArgument(0), null, null));
            return CompletableFuture.completedFuture(true);
        });
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
        requestHeaders(Map.of());
        when(incoming.startAsync(incoming, outgoing)).thenReturn(async);
        when(async.getRequest()).thenReturn(incoming);
        when(async.getResponse()).thenReturn(outgoing);
        when(outgoing.getOutputStream()).thenReturn(output);
        when(outgoing.isCommitted()).thenAnswer(ignored -> output.committed.get());
        when(outgoing.getHeaderNames()).thenAnswer(ignored -> new ArrayList<>(headers.keySet()));
        when(outgoing.getHeaders(anyString())).thenAnswer(invocation -> header(invocation.getArgument(0)));
        when(outgoing.getHeader(anyString())).thenAnswer(invocation -> header(invocation.getArgument(0)).stream().findFirst().orElse(null));
        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            if (value == null) headers.remove(name);
            else headers.put(name, new ArrayList<>(List.of(value)));
            return null;
        }).when(outgoing).setHeader(anyString(), nullable(String.class));
        doAnswer(invocation -> {
            headers.computeIfAbsent(invocation.getArgument(0), ignored -> new ArrayList<>()).add(invocation.getArgument(1));
            return null;
        }).when(outgoing).addHeader(anyString(), anyString());
        doAnswer(invocation -> { status.set(invocation.getArgument(0)); return null; }).when(outgoing).setStatus(anyInt());
        doAnswer(invocation -> {
            headers.put("Content-Length", new ArrayList<>(List.of(Integer.toString(invocation.getArgument(0)))));
            return null;
        }).when(outgoing).setContentLength(anyInt());
        doAnswer(invocation -> {
            headers.put("Content-Length", new ArrayList<>(List.of(Long.toString(invocation.getArgument(0)))));
            return null;
        }).when(outgoing).setContentLengthLong(anyLong());
        doAnswer(invocation -> {
            headers.clear();
            status.set(0);
            output.bytes.reset();
            output.committed.set(false);
            return null;
        }).when(outgoing).reset();
        JettyProxyProperties properties = new JettyProxyProperties(URI.create("http://127.0.0.1:9000"),
                1024, 16_384, 1, 1, 1, 1, Duration.ofMillis(250), Duration.ofSeconds(30), Duration.ofSeconds(30));
        engine = new JettyProxyEngine(client, properties, customizers);
    }

    List<String> header(String name) { return List.copyOf(headers.getOrDefault(name, List.of())); }

    void requestHeaders(Map<String, List<String>> values) {
        when(incoming.getHeaderNames()).thenAnswer(ignored -> Collections.enumeration(values.keySet()));
        when(incoming.getHeader(anyString())).thenAnswer(invocation -> values.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(invocation.getArgument(0)))
                .findFirst().map(entry -> entry.getValue().getFirst()).orElse(null));
        when(incoming.getHeaders(anyString())).thenAnswer(invocation -> Collections.enumeration(values.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(invocation.getArgument(0)))
                .findFirst().map(Map.Entry::getValue).orElse(List.of())));
    }

    Response response(int code, int length, HttpFields extras) {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(code);
        when(response.getHeaders()).thenReturn(HttpFields.build(extras).put("Content-Length", Integer.toString(length)).asImmutable());
        return response;
    }

    void reply(int code, byte[] body, HttpFields extras) {
        Response response = response(code, body.length, extras);
        listener.get().onHeaders(response);
        listener.get().onContentSource(response, Content.Source.from(ByteBuffer.wrap(body)));
        listener.get().onSuccess(response);
        listener.get().onComplete(new Result(upstream, response));
    }

    void awaitDone(ProxyCall call) {
        // Manual ownership and Mockito's static Servlet-context binding belong
        // to the JUnit thread; Awaitility's default worker cannot inherit them.
        await().pollInSameThread().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            executor.drain();
            assertTrue(call.completion().toCompletableFuture().isDone());
        });
    }

    void assertAdmissionRecovered() {
        when(incoming.getMethod()).thenReturn("OPTIONS");
        requestHeaders(Map.of("Max-Forwards", List.of("0")));
        ProxyCall probe = engine.forward(incoming, outgoing, TARGET);
        executor.drain();
        assertEquals(200, probe.completion().toCompletableFuture().join().status());
        assertEquals(0, engine.activeRequests());
    }

    @Override public void close() {
        engine.close();
        executor.drain();
    }

    static final class Output extends ServletOutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final AtomicBoolean committed = new AtomicBoolean();
        volatile boolean ready = true;
        @Override public boolean isReady() { return ready; }
        @Override public void setWriteListener(WriteListener listener) { }
        @Override public void write(int value) { committed.set(true); bytes.write(value); }
        @Override public void write(byte[] value, int offset, int length) {
            committed.set(true);
            bytes.write(value, offset, length);
        }
        @Override public void flush() { committed.set(true); }
    }

    static final class ManualExecutor implements Executor {
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void drain() {
            int count = 0;
            Runnable task;
            while ((task = tasks.poll()) != null) {
                assertTrue(++count < 10_000, "Serialized policy event processing must converge");
                task.run();
            }
        }
    }
}
