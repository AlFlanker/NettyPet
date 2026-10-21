package dev.example.app.transport;

import dev.example.proxy.transport.DownstreamWriter;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.ee11.servlet.HttpInput;
import org.eclipse.jetty.ee11.servlet.ServletApiResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.eclipse.jetty.ee11.servlet.ServletContextResponse;
import org.eclipse.jetty.ee11.servlet.ServletCoreResponse;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Проверяет выбор пути I/O и возврат владения буфером только по уведомлению Jetty. */
class JettyProxyIoAdapterTest {
    private final JettyProxyIoAdapter adapter = JettyProxyIoAdapter.INSTANCE;

    @Test
    void directUploadRequiresTheKnownByteBufferImplementation() {
        assertTrue(adapter.supportsDirectInput(mock(HttpInput.class)));
        assertFalse(adapter.supportsDirectInput(mock(ServletInputStream.class)));
    }

    @Test
    void wrappedResponseKeepsItsServletOutputSemantics() {
        ServletApiResponse raw = mock(ServletApiResponse.class);
        HttpServletResponse wrapped = new HttpServletResponseWrapper(raw);
        assertNull(adapter.createWriter(mock(HttpServletRequest.class), wrapped));
        verifyNoInteractions(raw);
    }

    @Test
    void unknownServletResponseUsesPortablePath() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        assertNull(adapter.createWriter(mock(HttpServletRequest.class), response));
        verifyNoInteractions(response);
    }

    @Test
    void selectedStreamOrWriterUsesPortablePathWithoutTouchingOutput() {
        ServletApiResponse response = mock(ServletApiResponse.class);
        ServletContextResponse context = mock(ServletContextResponse.class);
        when(context.isWritingOrStreaming()).thenReturn(true);
        try (var contexts = mockStatic(ServletContextResponse.class);
             var core = mockStatic(ServletCoreResponse.class)) {
            contexts.when(() -> ServletContextResponse.getServletContextResponse(response)).thenReturn(context);
            assertNull(adapter.createWriter(mock(HttpServletRequest.class), response));
            core.verifyNoInteractions();
            verifyNoInteractions(response);
        }
    }

    @Test
    void rawResponseUsesTheServletAwareCoreAdapterWithoutSelectingOutput() {
        ServletApiResponse response = mock(ServletApiResponse.class);
        ServletContextResponse context = mock(ServletContextResponse.class);
        ServletContextRequest coreRequest = mock(ServletContextRequest.class);
        Response coreResponse = mock(Response.class);
        when(context.getRequest()).thenReturn(coreRequest);
        try (var contexts = mockStatic(ServletContextResponse.class);
             var core = mockStatic(ServletCoreResponse.class)) {
            contexts.when(() -> ServletContextResponse.getServletContextResponse(response)).thenReturn(context);
            core.when(() -> ServletCoreResponse.wrap(coreRequest, response, false)).thenReturn(coreResponse);

            assertNotNull(adapter.createWriter(mock(HttpServletRequest.class), response));

            core.verify(() -> ServletCoreResponse.wrap(coreRequest, response, false));
            verifyNoInteractions(response, coreResponse);
        }
    }

    @Test
    void nativeWriteKeepsTheSameBufferUntilTheRealCompletion() {
        Fixture f = fixture();
        assertTrue(f.writer.flushesOnWrite(), "Core Response.write must not need a second empty write");
        ByteBuffer payload = ByteBuffer.allocateDirect(32);
        payload.position(3).limit(19);
        DownstreamWriter.Completion completion = mock(DownstreamWriter.Completion.class);

        f.writer.write(payload, completion);

        verify(f.core).write(eq(false), same(payload), any(Callback.class));
        verifyNoInteractions(completion);
        assertEquals(3, payload.position());
        assertEquals(19, payload.limit());
        assertEquals(Callback.InvocationType.NON_BLOCKING, f.callback.get().getInvocationType());

        f.callback.get().succeeded();
        f.callback.get().succeeded();
        f.callback.get().failed(new IOException("late failure"));
        verify(completion, times(1)).complete(null);
        verifyNoMoreInteractions(completion);
    }

    @Test
    void failedWriteReturnsOwnershipExactlyOnceAndPreservesFailure() {
        Fixture f = fixture();
        DownstreamWriter.Completion completion = mock(DownstreamWriter.Completion.class);
        IOException failure = new IOException("connection reset");
        f.writer.write(ByteBuffer.allocateDirect(16), completion);

        verifyNoInteractions(completion);
        f.callback.get().failed(failure);
        f.callback.get().succeeded();
        f.callback.get().failed(failure);

        verify(completion, times(1)).complete(same(failure));
        verifyNoMoreInteractions(completion);
    }

    @Test
    void flushWaitsForNativeCallbackWithoutSendingTheLastMarker() {
        Fixture f = fixture();
        DownstreamWriter.Completion completion = mock(DownstreamWriter.Completion.class);
        f.writer.flush(completion);

        verify(f.core).write(eq(false), argThat(buffer -> !buffer.hasRemaining()), any(Callback.class));
        verifyNoInteractions(completion);
        f.callback.get().succeeded();
        verify(completion).complete(null);
    }

    @Test
    void outputSelectedAfterFactoryCannotEnterJettysBlockingFallback() {
        Fixture f = fixture();
        when(f.context.isWritingOrStreaming()).thenReturn(true);
        DownstreamWriter.Completion writeCompletion = mock(DownstreamWriter.Completion.class);
        DownstreamWriter.Completion flushCompletion = mock(DownstreamWriter.Completion.class);

        f.writer.write(ByteBuffer.allocateDirect(16), writeCompletion);
        f.writer.flush(flushCompletion);

        verify(writeCompletion).complete(isA(IllegalStateException.class));
        verify(flushCompletion).complete(isA(IllegalStateException.class));
        verifyNoInteractions(f.core);
    }

    private static Fixture fixture() {
        ServletContextResponse context = mock(ServletContextResponse.class);
        Response core = mock(Response.class);
        AtomicReference<Callback> callback = new AtomicReference<>();
        doAnswer(call -> {
            callback.set(call.getArgument(2));
            return null;
        }).when(core).write(anyBoolean(), any(ByteBuffer.class), any(Callback.class));
        return new Fixture(context, core, callback, new JettyProxyIoAdapter.Writer(context, core));
    }

    private record Fixture(ServletContextResponse context, Response core,
                           AtomicReference<Callback> callback, JettyProxyIoAdapter.Writer writer) { }
}
