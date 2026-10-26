package dev.example.jettyclient.proxy;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A final response and the request sender have independently ordered completion signals. */
@Timeout(10)
class ProxyFetchCallbackOrderTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesACompleteResponseOnSenderOnlyFailureButNeverHidesAResponseFailure(boolean responseFails)
            throws Exception {
        HttpClient client = mock(HttpClient.class);
        Request upstream = mock(Request.class, RETURNS_SELF);
        AtomicReference<Response.Listener> listener = new AtomicReference<>();
        when(client.getExecutor()).thenReturn((Executor)Runnable::run);
        when(client.getByteBufferPool()).thenReturn(ByteBufferPool.NON_POOLING);
        when(client.newRequest(any(URI.class))).thenReturn(upstream);
        when(upstream.abort(any(Throwable.class))).thenReturn(CompletableFuture.completedFuture(true));
        doAnswer(invocation -> {
            listener.set((Response.Listener)invocation.getArgument(0, Response.CompleteListener.class));
            return null;
        }).when(upstream).send(any(Response.CompleteListener.class));

        try (ProxyFetchClient fetch = new ProxyFetchClient(client, JettyProxyProperties.defaults(), new ProxyFetchOptions(1, 1, 1))) {
            ProxyFetchCall call = fetch.fetch(ProxyFetchRequest.builder(URI.create("http://upstream.example/rejected"))
                    .timeout(Duration.ofSeconds(5)).build());
            assertNotNull(listener.get());
            byte[] body = "request rejected by backend".getBytes(StandardCharsets.UTF_8);
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(403);
            when(response.getHeaders()).thenReturn(HttpFields.build()
                    .put("Content-Length", Integer.toString(body.length))
                    .add("Set-Cookie", "first=1; Path=/")
                    .add("Set-Cookie", "second=2; Path=/").asImmutable());
            listener.get().onHeaders(response);
            listener.get().onContentSource(response, Content.Source.from(ByteBuffer.wrap(body)));
            if (!responseFails) listener.get().onSuccess(response);
            IOException senderFailure = new IOException("Sender failed after the final response arrived");
            IOException responseFailure = responseFails ? new IOException("Response transfer failed") : null;
            listener.get().onComplete(new Result(upstream, senderFailure, response, responseFailure));

            if (responseFails) {
                Throwable failure = call.completion().handle((answer, problem) -> problem)
                        .toCompletableFuture().get(3, TimeUnit.SECONDS);
                while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
                assertSame(responseFailure, failure);
            } else {
                ProxyFetchResult answer = call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(403, answer.status());
                assertEquals("request rejected by backend", answer.bodyAsString());
                assertEquals(List.of("first=1; Path=/", "second=2; Path=/"), answer.headerValues("Set-Cookie"));
            }
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertEquals(0, fetch.activeRequests()));
        }
    }
}
