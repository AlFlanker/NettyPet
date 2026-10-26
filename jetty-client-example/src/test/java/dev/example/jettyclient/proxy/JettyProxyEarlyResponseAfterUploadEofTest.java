package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
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
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.ee11.servlet.ServletChannel;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Drives a legal Jetty completion ordering without requiring a timing-dependent socket reset. */
@Timeout(10)
class JettyProxyEarlyResponseAfterUploadEofTest {
    @Test
    void fullEarlyResponseSurvivesSenderFailureAfterServletEofButBeforeRequestSuccess() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        HttpClient client = mock(HttpClient.class);
        Request upstream = mock(Request.class, RETURNS_SELF);
        AtomicReference<Request.Content> requestContent = new AtomicReference<>();
        AtomicReference<Response.Listener> listener = new AtomicReference<>();
        when(client.getExecutor()).thenReturn(executor);
        when(client.getByteBufferPool()).thenReturn(ByteBufferPool.NON_POOLING);
        when(client.newRequest(any(URI.class))).thenReturn(upstream);
        when(upstream.abort(any(Throwable.class))).thenReturn(CompletableFuture.completedFuture(false));
        when(upstream.fail(any(Throwable.class))).thenAnswer(invocation -> {
            requestContent.get().fail(invocation.getArgument(0));
            return CompletableFuture.completedFuture(true);
        });
        doAnswer(invocation -> {
            requestContent.set(invocation.getArgument(0));
            return upstream;
        }).when(upstream).body(any(Request.Content.class));
        doAnswer(invocation -> {
            listener.set((Response.Listener)invocation.getArgument(0, Response.CompleteListener.class));
            return null;
        }).when(upstream).send(any(Response.CompleteListener.class));

        byte[] upload = "small chunked upload".getBytes(StandardCharsets.US_ASCII);
        Input input = new Input(upload);
        AtomicBoolean committed = new AtomicBoolean();
        Output output = new Output(committed);
        HttpServletRequest incoming = mock(HttpServletRequest.class);
        HttpServletResponse outgoing = mock(HttpServletResponse.class);
        AsyncContext async = mock(AsyncContext.class);
        when(incoming.getMethod()).thenReturn("POST");
        when(incoming.isAsyncSupported()).thenReturn(true);
        when(incoming.getRequestURI()).thenReturn("/proxy/early-after-eof");
        when(incoming.getContextPath()).thenReturn("");
        when(incoming.getHeader("Transfer-Encoding")).thenReturn("chunked");
        when(incoming.getHeaderNames()).thenReturn(Collections.enumeration(List.of("Transfer-Encoding")));
        when(incoming.getHeaders(anyString())).thenAnswer(invocation -> Collections.enumeration(
                "Transfer-Encoding".equalsIgnoreCase(invocation.getArgument(0)) ? List.of("chunked") : List.of()));
        when(incoming.getContentLengthLong()).thenReturn(-1L);
        when(incoming.getInputStream()).thenReturn(input);
        when(incoming.startAsync(incoming, outgoing)).thenReturn(async);
        when(outgoing.getOutputStream()).thenReturn(output);
        when(outgoing.isCommitted()).thenAnswer(ignored -> committed.get());

        ServletContextRequest contextRequest = mock(ServletContextRequest.class);
        ServletChannel servletChannel = mock(ServletChannel.class);
        when(contextRequest.getServletChannel()).thenReturn(servletChannel);
        JettyProxyProperties properties = new JettyProxyProperties(URI.create("http://127.0.0.1:9000"),
                1024, 16_384, 1, 1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));
        JettyProxyServlet servlet = new JettyProxyServlet(client, properties);

        try (MockedStatic<ServletContextRequest> contexts = mockStatic(ServletContextRequest.class)) {
            contexts.when(() -> ServletContextRequest.getServletContextRequest(incoming)).thenReturn(contextRequest);
            try {
                servlet.service(incoming, outgoing);
                executor.drain();
                assertNotNull(listener.get());
                AsyncRequestContent body = assertInstanceOf(AsyncRequestContent.class, requestContent.get());

                // The sender finishes the data chunk, permitting the Servlet producer to observe EOF.
                // It has not yet consumed/transmitted the terminating chunk or reported request success.
                Content.Chunk data = body.read();
                assertNotNull(data);
                assertFalse(data.isLast());
                byte[] actualUpload = new byte[data.remaining()];
                data.getByteBuffer().get(actualUpload);
                assertArrayEquals(upload, actualUpload);
                data.release();
                executor.drain();
                assertTrue(input.isFinished());
                assertTrue(body.isClosed(), "The producer's content.close() must precede the final response");
                assertEquals(1, body.count(), "The sender still has the terminating content chunk to process");

                byte[] reply = "too-large".getBytes(StandardCharsets.US_ASCII);
                Response response = mock(Response.class);
                when(response.getStatus()).thenReturn(413);
                when(response.getHeaders()).thenReturn(HttpFields.build()
                        .put("Content-Length", Integer.toString(reply.length)).asImmutable());
                listener.get().onHeaders(response);
                listener.get().onContentSource(response, Content.Source.from(ByteBuffer.wrap(reply)));
                executor.drain();
                assertArrayEquals(reply, output.bytes.toByteArray());
                assertTrue(committed.get());

                listener.get().onSuccess(response);
                executor.drain();
                verify(async, never()).complete();

                // Jetty Result explicitly permits successful response + failed request.
                // Here the upstream rejected the request before its terminating write succeeded.
                IOException senderFailure = new IOException("Upstream stopped receiving the terminating chunk");
                body.fail(senderFailure);
                listener.get().onComplete(new Result(upstream, senderFailure, response, null));
                executor.drain();

                verify(async, times(1)).complete();
                verify(servletChannel, never()).abort(any(Throwable.class));
                assertArrayEquals(reply, output.bytes.toByteArray());
                assertEquals(0, servlet.activeRequests());
            } finally {
                servlet.destroy();
                executor.drain();
            }
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override public void execute(Runnable task) { tasks.addLast(task); }

        void drain() {
            int count = 0;
            Runnable task;
            while ((task = tasks.pollFirst()) != null) {
                assertTrue(++count < 100, "The fixture must not generate an unbounded callback loop");
                task.run();
            }
        }
    }

    private static final class Input extends ServletInputStream {
        private final byte[] bytes;
        private int position;

        Input(byte[] bytes) { this.bytes = bytes; }

        @Override public boolean isFinished() { return position == bytes.length; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { }
        @Override public int read() { throw new AssertionError("A bulk read was expected"); }

        @Override public int read(byte[] target, int offset, int length) {
            if (isFinished()) return -1;
            int count = Math.min(length, bytes.length - position);
            System.arraycopy(bytes, position, target, offset, count);
            position += count;
            return count;
        }
    }

    private static final class Output extends ServletOutputStream {
        private final AtomicBoolean committed;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Output(AtomicBoolean committed) { this.committed = committed; }

        @Override public boolean isReady() { return true; }
        @Override public void setWriteListener(WriteListener listener) { }
        @Override public void write(int value) { throw new AssertionError("A bulk write was expected"); }
        @Override public void write(byte[] source, int offset, int length) { bytes.write(source, offset, length); }
        @Override public void flush() { committed.set(true); }
    }
}
