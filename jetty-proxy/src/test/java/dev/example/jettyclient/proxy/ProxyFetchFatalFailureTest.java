package dev.example.jettyclient.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Safe synthetic fatal errors exercise propagation without exhausting VM resources. */
@Timeout(10)
class ProxyFetchFatalFailureTest {
    private static final URI TARGET = URI.create("http://upstream.example/resource");

    @Test
    void rejectsAnIncompatibleClientBeforeAllocatingItsWorkers() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ProxyFetchClient(new HttpClient(), JettyProxyProperties.defaults()));
        assertTrue(failure.getMessage().contains("JettyProxyClientFactory.createStarted"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFatalStartFailureReleasesAdmissionAndEscapesTheCaller(boolean threadDeath) {
        HttpClient client = compatibleClient();
        Error fatal = fatal(threadDeath);
        when(client.newRequest(any(URI.class))).thenThrow(fatal);

        try (ProxyFetchClient fetch = new ProxyFetchClient(client, JettyProxyProperties.defaults())) {
            assertSame(fatal, assertThrows(Error.class, () -> fetch.fetch(request())));
            assertEquals(0, fetch.activeRequests());

            // A local exchange avoids the failing transport and proves admission remains usable.
            var local = ProxyFetchRequest.builder(TARGET).method("OPTIONS")
                    .headers(java.util.Map.of("Max-Forwards", java.util.List.of("0"))).build();
            assertEquals(200, fetch.fetch(local).completion().toCompletableFuture().join().status());
            assertEquals(0, fetch.activeRequests());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void aFatalContentFailureCompletesItsHandleDrainsAbortAndEscapes(boolean threadDeath, boolean blocking)
            throws Exception {
        HttpClient client = compatibleClient();
        Request upstream = mock(Request.class, RETURNS_SELF);
        when(client.newRequest(any(URI.class))).thenReturn(upstream);
        AtomicReference<Response.Listener> listener = new AtomicReference<>();
        doAnswer(invocation -> {
            listener.set((Response.Listener)invocation.getArgument(0, Response.CompleteListener.class));
            return null;
        }).when(upstream).send(any(Response.CompleteListener.class));
        doAnswer(invocation -> {
            listener.get().onComplete(new Result(upstream, invocation.getArgument(0), null, null));
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }).when(upstream).abort(any(Throwable.class));
        Error fatal = fatal(threadDeath);
        Content.Source source = mock(Content.Source.class);
        when(source.read()).thenThrow(fatal);
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(200);
        when(response.getHeaders()).thenReturn(HttpFields.build().put("Content-Type", "text/plain").asImmutable());

        try (ProxyFetchClient fetch = new ProxyFetchClient(client, JettyProxyProperties.defaults());
             var caller = Executors.newSingleThreadExecutor()) {
            ProxyFetchCall call = blocking ? null : fetch.fetch(request());
            var waiting = blocking ? caller.submit(() -> fetch.fetchBlocking(request())) : null;
            await().atMost(Duration.ofSeconds(3)).until(() -> listener.get() != null);
            listener.get().onHeaders(response);

            assertSame(fatal, assertThrows(Error.class, () -> listener.get().onContentSource(response, source)));
            if (blocking) {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> waiting.get(3, TimeUnit.SECONDS));
                assertSame(fatal, failure.getCause(), "The blocking adapter must not wrap a fatal error as IOException");
            } else {
                assertTrue(call.completion().toCompletableFuture().isDone());
                CompletionException failure = assertThrows(CompletionException.class,
                        () -> call.completion().toCompletableFuture().join());
                assertSame(fatal, failure.getCause());
            }
            assertEquals(0, fetch.activeRequests(), "Synchronous abort completion must be drained before rethrow");

            // A late callback must still acquire the serialized owner after the fatal event.
            assertDoesNotThrow(() -> listener.get().onComplete(new Result(upstream, response)));
            assertEquals(0, fetch.activeRequests());
            verify(upstream, times(1)).abort(fatal);
        }
    }

    private static HttpClient compatibleClient() {
        HttpClient client = mock(ProxyHttpClient.class);
        when(client.getExecutor()).thenReturn(Runnable::run);
        when(client.getByteBufferPool()).thenReturn(ByteBufferPool.NON_POOLING);
        return client;
    }

    private static ProxyFetchRequest request() { return ProxyFetchRequest.builder(TARGET).build(); }

    @SuppressWarnings("removal")
    private static Error fatal(boolean threadDeath) {
        return threadDeath ? new ThreadDeath() : new SyntheticFatal();
    }

    private static final class SyntheticFatal extends VirtualMachineError {
        SyntheticFatal() { super("Safe synthetic fatal error"); }
    }
}
