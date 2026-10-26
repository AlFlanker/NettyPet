package dev.example.jettyclient.proxy;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.ee11.servlet.ServletChannel;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Error rendering belongs to the exchange owner before commit and shares its cleanup barrier. */
@Timeout(10)
class JettyProxyErrorPolicyTest {
    @Test
    void rendersBoundedUtf8BeforeCommitAndRetainsApplicationHeadersAndOriginalFailure() throws Exception {
        try (PolicyTestExchange fixture = new PolicyTestExchange()) {
            fixture.headers.put("Set-Cookie", List.of("application=1; Path=/"));
            fixture.headers.put("X-Application-Security", List.of("retained"));
            IllegalStateException failure = new IllegalStateException("Upstream setup failed");
            when(fixture.client.newRequest(any(URI.class))).thenThrow(failure);
            AtomicInteger calls = new AtomicInteger();
            String message = "Подсистема временно недоступна";
            byte[] expected = message.getBytes(StandardCharsets.UTF_8);
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(PolicyTestExchange.TARGET).errorPolicy((metadata, status, cause) -> {
                        calls.incrementAndGet();
                        assertEquals(502, status);
                        assertSame(failure, cause);
                        return ProxyErrorResponse.text(500, message);
                    }).build());
            fixture.executor.drain();

            ProxyExchangeResult result = call.completion().toCompletableFuture().join();
            assertEquals(500, result.status());
            assertSame(failure, result.failure());
            assertFalse(result.success());
            assertEquals(expected.length, result.downloadedBytes());
            assertEquals(1, calls.get());
            assertArrayEquals(expected, fixture.output.bytes.toByteArray());
            assertEquals(List.of(Integer.toString(expected.length)), fixture.header("Content-Length"));
            assertEquals(List.of("text/plain; charset=UTF-8"), fixture.header("Content-Type"));
            assertEquals(List.of("application=1; Path=/"), fixture.header("Set-Cookie"));
            assertEquals(List.of("retained"), fixture.header("X-Application-Security"));
            assertEquals(List.of("no-store"), fixture.header("Cache-Control"));
            verify(fixture.async, times(1)).complete();
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void timeoutCanUseLegacy408TextWithoutAnExternalFallbackWriter() throws Exception {
        CompletableFuture<ProxyHeaderPatch> stage = new CompletableFuture<>();
        try (PolicyTestExchange fixture = new PolicyTestExchange()) {
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(PolicyTestExchange.TARGET).timeout(Duration.ofMillis(40))
                            .requestHeadersAsync(metadata -> stage)
                            .errorPolicy((metadata, status, cause) -> {
                                assertEquals(504, status);
                                assertInstanceOf(java.util.concurrent.TimeoutException.class, cause);
                                return ProxyErrorResponse.text(408, "timeout");
                            }).build());
            fixture.executor.drain();
            fixture.awaitDone(call);

            assertEquals(408, call.completion().toCompletableFuture().join().status());
            assertEquals("timeout", fixture.output.bytes.toString(StandardCharsets.UTF_8));
            verify(fixture.client, never()).newRequest(any(URI.class));
            verify(fixture.async, times(1)).complete();
            assertEquals(0, fixture.engine.activeRequests());
            stage.complete(ProxyHeaderPatch.builder().set("X-Late", "ignored").build());
            fixture.executor.drain();
            assertTrue(fixture.header("X-Late").isEmpty());
        }
    }

    @Test
    void committedResponseAbortsWithoutInvokingTheErrorRendererOrChangingItsStatus() throws Exception {
        AtomicInteger rendered = new AtomicInteger();
        try (MockedStatic<ServletContextRequest> contexts = mockStatic(ServletContextRequest.class);
             PolicyTestExchange fixture = new PolicyTestExchange()) {
            ServletContextRequest context = mock(ServletContextRequest.class);
            ServletChannel channel = mock(ServletChannel.class);
            when(context.getServletChannel()).thenReturn(channel);
            contexts.when(() -> ServletContextRequest.getServletContextRequest(fixture.incoming)).thenReturn(context);
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(PolicyTestExchange.TARGET).errorPolicy((metadata, status, cause) -> {
                        rendered.incrementAndGet();
                        return ProxyErrorResponse.text(500, "must not append a second response");
                    }).build());
            fixture.executor.drain();
            byte[] prefix = "already sent".getBytes(StandardCharsets.US_ASCII);
            Response response = fixture.response(200, prefix.length + 20, HttpFields.EMPTY);
            fixture.listener.get().onHeaders(response);
            fixture.listener.get().onContentSource(response, Content.Source.from(ByteBuffer.wrap(prefix)));
            fixture.executor.drain();
            assertTrue(fixture.output.committed.get());
            IOException failure = new IOException("Truncated upstream");
            fixture.listener.get().onComplete(new Result(fixture.upstream, null, response, failure));
            fixture.executor.drain();

            ProxyExchangeResult result = call.completion().toCompletableFuture().join();
            assertEquals(200, result.status(), "The wire status cannot change after commit");
            assertFalse(result.success());
            assertEquals(0, rendered.get());
            assertArrayEquals(prefix, fixture.output.bytes.toByteArray());
            verify(channel, times(1)).abort(failure);
            verify(fixture.async, never()).complete();
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void failingRendererFallsBackToAnEmpty500AndOversizedResponsesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new ProxyErrorResponse(500, Map.of(), new byte[65_537]));
        assertThrows(IllegalArgumentException.class,
                () -> new ProxyErrorResponse(500, Map.of("Content-Length", List.of("1")), new byte[1]));
        try (PolicyTestExchange fixture = new PolicyTestExchange()) {
            when(fixture.client.newRequest(any(URI.class))).thenThrow(new IllegalStateException("Upstream failed"));
            AtomicInteger rendered = new AtomicInteger();
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(PolicyTestExchange.TARGET).errorPolicy((metadata, status, cause) -> {
                        rendered.incrementAndGet();
                        throw new IllegalStateException("Template failed");
                    }).build());
            fixture.executor.drain();
            assertEquals(500, call.completion().toCompletableFuture().join().status());
            assertFalse(call.completion().toCompletableFuture().join().success());
            assertEquals(0, fixture.output.bytes.size());
            assertEquals(List.of("0"), fixture.header("Content-Length"));
            assertEquals(1, rendered.get());
            verify(fixture.async, times(1)).complete();
            assertEquals(0, fixture.engine.activeRequests());
            fixture.assertAdmissionRecovered();
        }
    }

    @Test
    void headErrorPublishesItsRepresentationLengthWithoutWritingBodyBytes() throws Exception {
        try (PolicyTestExchange fixture = new PolicyTestExchange()) {
            when(fixture.incoming.getMethod()).thenReturn("HEAD");
            when(fixture.client.newRequest(any(URI.class))).thenThrow(new IllegalStateException("Unavailable"));
            String message = "Недоступно";
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(PolicyTestExchange.TARGET)
                            .errorPolicy((metadata, status, cause) -> ProxyErrorResponse.text(503, message)).build());
            fixture.executor.drain();
            assertEquals(503, call.completion().toCompletableFuture().join().status());
            assertEquals(0, call.completion().toCompletableFuture().join().downloadedBytes());
            assertEquals(0, fixture.output.bytes.size());
            assertEquals(List.of(Integer.toString(message.getBytes(StandardCharsets.UTF_8).length)),
                    fixture.header("Content-Length"));
            verify(fixture.async, times(1)).complete();
            assertEquals(0, fixture.engine.activeRequests());
        }
    }

    @Test
    void stalledErrorBodyHasItsOwnBoundedWriteBudgetAndCannotHoldAdmissionForever() throws Exception {
        try (MockedStatic<ServletContextRequest> contexts = mockStatic(ServletContextRequest.class);
             PolicyTestExchange fixture = new PolicyTestExchange()) {
            ServletContextRequest context = mock(ServletContextRequest.class);
            ServletChannel channel = mock(ServletChannel.class);
            when(context.getServletChannel()).thenReturn(channel);
            contexts.when(() -> ServletContextRequest.getServletContextRequest(fixture.incoming)).thenReturn(context);
            fixture.output.ready = false;
            when(fixture.client.newRequest(any(URI.class))).thenThrow(new IllegalStateException("Unavailable"));
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing,
                    ProxyRequestPlan.builder(PolicyTestExchange.TARGET)
                            .errorPolicy((metadata, status, cause) -> ProxyErrorResponse.text(500, "pending error body")).build());
            fixture.executor.drain();
            assertFalse(call.completion().toCompletableFuture().isDone(), "The error write initially waits for readiness");
            assertEquals(1, fixture.engine.activeRequests());
            fixture.awaitDone(call);

            assertFalse(call.completion().toCompletableFuture().join().success());
            assertEquals(0, fixture.engine.activeRequests());
            assertEquals(0, fixture.output.bytes.size());
            verify(channel, times(1)).abort(any(Throwable.class));
        }
    }
}
