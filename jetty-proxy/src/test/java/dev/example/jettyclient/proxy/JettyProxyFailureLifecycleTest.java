package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.ee11.servlet.ServletChannel;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Application wrappers must not leave an unbounded Servlet async after a failure. */
@Timeout(10)
class JettyProxyFailureLifecycleTest {
    @ParameterizedTest
    @EnumSource(FailureAt.class)
    void failedServletAccessStillAbortsTheDownstreamAndReleasesTheExchange(FailureAt failureAt) throws Exception {
        HttpClient client = mock(ProxyHttpClient.class);
        Request upstream = mock(Request.class, RETURNS_SELF);
        HttpServletRequest incoming = mock(HttpServletRequest.class);
        HttpServletResponse outgoing = mock(HttpServletResponse.class);
        AsyncContext async = mock(AsyncContext.class);
        AtomicReference<Response.Listener> listener = new AtomicReference<>();
        when(client.getExecutor()).thenReturn((Executor)Runnable::run);
        when(client.newRequest(any(URI.class))).thenReturn(upstream);
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
        when(incoming.startAsync(incoming, outgoing)).thenReturn(async);
        when(outgoing.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

        IllegalStateException wrapperFailure = new IllegalStateException("Application wrapper failed");
        switch (failureAt) {
            case COMMIT_QUERY -> when(outgoing.isCommitted()).thenThrow(wrapperFailure);
            case RESET -> doThrow(wrapperFailure).when(outgoing).reset();
            case COMPLETE -> doThrow(wrapperFailure).when(async).complete();
        }
        ServletContextRequest context = mock(ServletContextRequest.class);
        ServletChannel channel = mock(ServletChannel.class);
        when(context.getServletChannel()).thenReturn(channel);
        JettyProxyProperties properties = new JettyProxyProperties(URI.create("http://127.0.0.1:9000"),
                1024, 16_384, 1, 1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));

        try (MockedStatic<ServletContextRequest> contexts = mockStatic(ServletContextRequest.class);
             JettyProxyEngine engine = new JettyProxyEngine(client, properties)) {
            contexts.when(() -> ServletContextRequest.getServletContextRequest(incoming)).thenReturn(context);
            ProxyCall call = engine.forward(incoming, outgoing, URI.create("http://upstream.example/failure"));
            assertNotNull(listener.get());
            assertEquals(1, engine.activeRequests());
            IOException transportFailure = new IOException("Upstream failed before response headers");

            listener.get().onComplete(new Result(upstream, transportFailure, null, null));

            ProxyExchangeResult result = call.completion().toCompletableFuture().join();
            assertEquals(502, result.status());
            assertSame(transportFailure, result.failure());
            assertFalse(result.success());
            assertEquals(0, engine.activeRequests());
            verify(upstream).abort(transportFailure);
            // setTimeout(0) disables the container deadline; cancelling the engine
            // timer and releasing admission alone would leave the Servlet hanging.
            verify(async).setTimeout(0);
            verify(channel, times(1)).abort(transportFailure);
        }
    }

    private enum FailureAt { COMMIT_QUERY, RESET, COMPLETE }
}
