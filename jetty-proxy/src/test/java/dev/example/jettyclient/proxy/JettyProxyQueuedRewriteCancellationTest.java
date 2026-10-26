package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

/** Queued rewrites have no reader yet and need not retain buffers behind unrelated CPU work. */
@Timeout(15)
class JettyProxyQueuedRewriteCancellationTest {
    @Test
    void cancellingQueuedRewriteReleasesAdmissionBeforeBusyWorkersReturn() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.getExecutor()).thenReturn((Executor)Runnable::run);
        when(client.getByteBufferPool()).thenReturn(ByteBufferPool.NON_POOLING);
        JettyProxyProperties properties = new JettyProxyProperties(URI.create("http://upstream.example"),
                1024, 16_384, 3, 3, 3, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));
        JettyProxyEngine engine = new JettyProxyEngine(client, properties);
        CountDownLatch workersEntered = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        AtomicBoolean cancelledRewriterCalled = new AtomicBoolean();
        try {
            for (int index = 0; index < 2; index++) {
                ExchangeFixture fixture = new ExchangeFixture(client, "/busy-" + index);
                var plan = ProxyRequestPlan.builder(fixture.target).responseBody((request, response) ->
                        new ProxyResponseBodyPolicy.Selection(1024, 1024, (metadata, body) -> {
                            workersEntered.countDown();
                            assertTrue(releaseWorkers.await(10, TimeUnit.SECONDS));
                            return body;
                        })).build();
                fixture.startAndReply(engine, plan);
            }
            assertTrue(workersEntered.await(3, TimeUnit.SECONDS), "Both CPU workers must be occupied");
            ExchangeFixture queued = new ExchangeFixture(client, "/queued");
            var queuedPlan = ProxyRequestPlan.builder(queued.target).responseBody((request, response) ->
                    new ProxyResponseBodyPolicy.Selection(1024, 1024, (metadata, body) -> {
                        cancelledRewriterCalled.set(true);
                        return body;
                    })).build();
            ProxyCall queuedCall = queued.startAndReply(engine, queuedPlan);
            assertFalse(queuedCall.completion().toCompletableFuture().isDone());
            assertEquals(3, engine.activeRequests());

            IOException cancelled = new IOException("Queued request no longer needed");
            queuedCall.cancel(cancelled);

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertTrue(queuedCall.completion().toCompletableFuture().isDone(),
                            "A queued task must release its input without waiting for unrelated running workers"));
            ProxyExchangeResult result = queuedCall.completion().toCompletableFuture().join();
            assertEquals(503, result.status());
            assertSame(cancelled, result.failure());
            assertEquals(2, engine.activeRequests());
            assertFalse(cancelledRewriterCalled.get());

            ExchangeFixture probe = new ExchangeFixture(client, "/probe");
            ProxyCall probeCall = probe.startAndReply(engine, ProxyRequestPlan.builder(probe.target).build());
            assertTrue(probeCall.completion().toCompletableFuture().join().success(),
                    "An ordinary streaming request must be admitted while unrelated rewriters remain busy");
            assertEquals(2, engine.activeRequests());
        } finally {
            releaseWorkers.countDown();
            engine.close();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, engine.activeRequests()));
        }
        assertFalse(cancelledRewriterCalled.get(), "Dequeuing the cancelled task must not start user code later");
    }

    private static final class ExchangeFixture {
        final URI target;
        final HttpServletRequest incoming = mock(HttpServletRequest.class);
        final HttpServletResponse outgoing = mock(HttpServletResponse.class);
        final Request upstream = mock(Request.class, RETURNS_SELF);
        final AtomicReference<Response.Listener> listener = new AtomicReference<>();

        ExchangeFixture(HttpClient client, String path) throws Exception {
            target = URI.create("http://upstream.example" + path);
            when(client.newRequest(target)).thenReturn(upstream);
            when(upstream.abort(any(Throwable.class))).thenReturn(CompletableFuture.completedFuture(true));
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
            when(outgoing.getOutputStream()).thenReturn(new ServletOutputStream() {
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener(WriteListener listener) { }
                @Override public void write(int value) { }
                @Override public void write(byte[] bytes, int offset, int length) { }
            });
        }

        ProxyCall startAndReply(JettyProxyEngine engine, ProxyRequestPlan plan) {
            ProxyCall call = engine.forward(incoming, outgoing, plan);
            byte[] bytes = "retained input".getBytes(StandardCharsets.US_ASCII);
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            when(response.getHeaders()).thenReturn(HttpFields.build()
                    .put("Content-Length", Integer.toString(bytes.length)).asImmutable());
            listener.get().onHeaders(response);
            listener.get().onContentSource(response, Content.Source.from(ByteBuffer.wrap(bytes)));
            listener.get().onSuccess(response);
            listener.get().onComplete(new Result(upstream, response));
            return call;
        }
    }
}
