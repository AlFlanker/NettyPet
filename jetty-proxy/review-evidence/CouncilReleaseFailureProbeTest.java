package dev.example.jettyclient.proxy;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.ee11.servlet.ServletChannel;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Temporary evidence probe: assertions intentionally describe the current bad state, not the desired contract. */
@Timeout(10)
class CouncilReleaseFailureProbeTest {
    @Test
    void aStreamingChunkReleaseFailureStrandsWriteOwnershipAfterTheBytesWereWritten() throws Exception {
        try (MockedStatic<ServletContextRequest> contexts = mockStatic(ServletContextRequest.class);
             PolicyTestExchange fixture = new PolicyTestExchange()) {
            ServletContextRequest context = mock(ServletContextRequest.class);
            ServletChannel channel = mock(ServletChannel.class);
            when(context.getServletChannel()).thenReturn(channel);
            contexts.when(() -> ServletContextRequest.getServletContextRequest(fixture.incoming)).thenReturn(context);
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing, PolicyTestExchange.TARGET);
            fixture.executor.drain();
            byte[] bytes = "streamed chunk".getBytes(StandardCharsets.US_ASCII);
            IllegalStateException releaseFailure = new IllegalStateException("Injected streaming release failure");
            AtomicInteger releases = new AtomicInteger();
            Content.Chunk chunk = Content.Chunk.from(ByteBuffer.wrap(bytes), true, () -> {
                releases.incrementAndGet();
                throw releaseFailure;
            });
            Content.Source source = mock(Content.Source.class);
            when(source.read()).thenReturn(chunk, Content.Chunk.EOF);
            Response response = fixture.response(200, bytes.length, HttpFields.EMPTY);
            fixture.listener.get().onHeaders(response);
            fixture.listener.get().onContentSource(response, source);
            fixture.listener.get().onSuccess(response);
            fixture.listener.get().onComplete(new Result(fixture.upstream, response));
            fixture.executor.drain();

            assertArrayEquals(bytes, fixture.output.bytes.toByteArray());
            assertEquals(1, releases.get());
            assertEquals(1, fixture.engine.activeRequests(), "BUG: the exchange is still registered after all callbacks");
            assertFalse(call.completion().toCompletableFuture().isDone(), "BUG: cleanup failure strands the outcome");
            Object exchange = exchange(fixture.engine);
            assertEquals(Boolean.TRUE, field(exchange, "terminal"));
            assertEquals(Boolean.TRUE, field(exchange, "upstreamDone"));
            assertEquals(Boolean.TRUE, field(exchange, "writePending"));
            assertEquals(1, ((AtomicInteger)field(exchange, "retainedChunks")).get());
        }
    }

    @Test
    void anUploadPoolReleaseFailureStrandsUploadOwnershipAfterTheSenderReleasedItsChunk() throws Exception {
        try (PolicyTestExchange fixture = new PolicyTestExchange()) {
            byte[] upload = "upload chunk".getBytes(StandardCharsets.US_ASCII);
            Input input = new Input(upload);
            AtomicReference<Request.Content> content = new AtomicReference<>();
            AtomicInteger releases = new AtomicInteger();
            IllegalStateException releaseFailure = new IllegalStateException("Injected upload release failure");
            ByteBufferPool pool = new ByteBufferPool() {
                @Override public RetainableByteBuffer.Mutable acquire(int size, boolean direct) {
                    assertFalse(direct);
                    return RetainableByteBuffer.wrap(ByteBuffer.allocate(size), () -> {
                        releases.incrementAndGet();
                        throw releaseFailure;
                    });
                }
                @Override public void clear() { }
            };
            when(fixture.client.getByteBufferPool()).thenReturn(pool);
            when(fixture.incoming.getMethod()).thenReturn("POST");
            when(fixture.incoming.getContentLengthLong()).thenReturn((long)upload.length);
            when(fixture.incoming.getInputStream()).thenReturn(input);
            doAnswer(invocation -> { content.set(invocation.getArgument(0)); return fixture.upstream; })
                    .when(fixture.upstream).body(any(Request.Content.class));
            ProxyCall call = fixture.engine.forward(fixture.incoming, fixture.outgoing, PolicyTestExchange.TARGET);
            fixture.executor.drain();
            AsyncRequestContent sender = assertInstanceOf(AsyncRequestContent.class, content.get());
            Content.Chunk data = sender.read();
            assertNotNull(data);
            assertEquals(upload.length, data.remaining());
            data.release();
            fixture.executor.drain();

            assertEquals(1, releases.get());
            verify(fixture.async, times(1)).complete();
            assertEquals(1, fixture.engine.activeRequests(), "BUG: upload ownership survives its failed cleanup");
            assertFalse(call.completion().toCompletableFuture().isDone());
            Object exchange = exchange(fixture.engine);
            assertEquals(Boolean.TRUE, field(exchange, "terminal"));
            assertEquals(Boolean.TRUE, field(exchange, "upstreamDone"));
            assertEquals(Boolean.TRUE, field(exchange, "uploadPending"));
        }
    }

    private static Object exchange(JettyProxyEngine engine) throws Exception {
        var exchanges = JettyProxyEngine.class.getDeclaredField("exchanges");
        exchanges.setAccessible(true);
        return ((Set<?>)exchanges.get(engine)).iterator().next();
    }
    private static Object field(Object exchange, String name) throws Exception {
        var member = exchange.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(exchange);
    }
    private static final class Input extends ServletInputStream {
        private final byte[] bytes;
        private int position;
        Input(byte[] bytes) { this.bytes = bytes; }
        @Override public boolean isFinished() { return position == bytes.length; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { }
        @Override public int read() { throw new AssertionError("Expected a bulk read"); }
        @Override public int read(byte[] target, int offset, int length) {
            if (isFinished()) return -1;
            int count = Math.min(length, bytes.length - position);
            System.arraycopy(bytes, position, target, offset, count);
            position += count;
            return count;
        }
    }
}
