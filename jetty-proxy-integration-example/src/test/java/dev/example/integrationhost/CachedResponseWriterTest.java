package dev.example.integrationhost;

import com.otr.portal.proxy.caching.CachedResponseWriter;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CachedResponseWriterTest {
    @Test void eightMiBCacheHitWaitsForReadinessAndPreservesExactBytesWithBoundedWrites() throws Exception {
        byte[] content = payload(8 * 1024 * 1024);
        Fixture f = new Fixture(content);
        f.output.listener.onWritePossible();
        assertEquals(0, f.output.bytes);
        assertEquals(0, f.completed.get());
        f.drain();
        assertEquals(content.length, f.output.bytes);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(content), f.output.digest.digest());
        assertEquals(CachedResponseWriter.CHUNK_BYTES, f.output.maxWrite);
        assertEquals(content.length / CachedResponseWriter.CHUNK_BYTES, f.output.writes);
        assertEquals(1, f.completed.get());
        verify(f.async, times(1)).complete();
    }

    @Test void LastWriteAndFlushMustBothBecomeReadyBeforeCompletion() throws Exception {
        Fixture f = new Fixture(payload(7));
        f.output.pauseFlush = true;
        f.resume();
        assertEquals(7, f.output.bytes);
        assertEquals(0, f.completed.get());
        f.resume();
        assertEquals(1, f.output.flushes);
        assertEquals(0, f.completed.get());
        f.resume();
        assertEquals(1, f.completed.get());
        assertEquals(1, f.output.flushes);
    }

    @Test void StreamErrorFinishesOnceAndIgnoresLateReadiness() throws Exception {
        Fixture f = new Fixture(payload(128 * 1024));
        f.resume();
        long written = f.output.bytes;
        f.output.listener.onError(new IOException("disconnected"));
        f.lifecycle.onError(new AsyncEvent(f.async));
        f.output.recycled = true;
        f.output.listener.onWritePossible();
        assertEquals(written, f.output.bytes);
        assertEquals(1, f.completed.get());
        verify(f.async, times(1)).complete();
    }

    @Test void AsyncTimeoutReleasesPendingHitWithoutWritingAfterRecycling() throws Exception {
        Fixture f = new Fixture(payload(64));
        f.lifecycle.onTimeout(new AsyncEvent(f.async));
        f.output.recycled = true;
        f.output.listener.onWritePossible();
        f.output.listener.onError(new IOException("late error"));
        assertEquals(0, f.output.bytes);
        assertEquals(1, f.completed.get());
        verify(f.async, times(1)).complete();
    }

    @Test void ExternalCompletionDoesNotCompleteContextAgainOrTouchRecycledOutput() throws Exception {
        Fixture f = new Fixture(payload(64));
        f.lifecycle.onComplete(new AsyncEvent(f.async));
        f.output.recycled = true;
        f.output.listener.onWritePossible();
        f.output.listener.onError(new IOException("late"));
        assertEquals(1, f.completed.get());
        verify(f.async, never()).complete();
    }

    @Test void SynchronousWriteFailureHasOneTerminalCallback() throws Exception {
        Fixture f = new Fixture(payload(64));
        f.output.failWrite = true;
        f.resume();
        f.output.listener.onError(new IOException("duplicate"));
        assertEquals(0, f.output.bytes);
        assertEquals(1, f.completed.get());
        verify(f.async, times(1)).complete();
    }

    @Test void FatalReadinessWriteAndFlushErrorsCleanUpBeforeBeingRethrown() throws Exception {
        for (String stage : new String[]{"ready", "write", "flush"}) {
            Fixture f = new Fixture(payload(64));
            FatalProbe fatal = new FatalProbe(stage);
            f.output.fatalStage = stage;
            f.output.fatal = fatal;
            if (stage.equals("flush")) f.resume(); // finish writing before testing the next readiness/flush callback
            assertSame(fatal, assertThrows(FatalProbe.class, f::resume));
            assertEquals(1, f.completed.get());
            verify(f.async, times(1)).complete();
            f.output.recycled = true;
            assertDoesNotThrow(f.output.listener::onWritePossible);
        }
    }

    @Test void SetupFailuresCompleteAvailableContextAndPreserveThePrimaryException() throws Exception {
        for (String stage : new String[]{"output", "async-listener", "write-listener"}) {
            var async = mock(AsyncContext.class);
            var response = mock(HttpServletResponse.class);
            var output = mock(ServletOutputStream.class);
            var finished = new AtomicInteger();
            Throwable primary = stage.equals("output") ? new IOException("output failed")
                    : stage.equals("async-listener") ? new IllegalStateException("listener failed")
                    : new FatalProbe("write-listener failed");
            if (stage.equals("output")) when(response.getOutputStream()).thenThrow(primary);
            else {
                when(response.getOutputStream()).thenReturn(output);
                if (stage.equals("async-listener")) doThrow(primary).when(async).addListener(any(AsyncListener.class));
                else doThrow(primary).when(output).setWriteListener(any(WriteListener.class));
            }
            assertSame(primary, assertThrows(primary.getClass(),
                    () -> CachedResponseWriter.write(async, response, payload(64), finished::incrementAndGet)));
            assertEquals(1, finished.get());
            verify(async, times(1)).complete();
        }
    }

    @Test void CleanupFailureIsSuppressedOnFatalWriteFailure() throws Exception {
        Fixture f = new Fixture(payload(64));
        f.output.fatalStage = "write";
        f.output.fatal = new FatalProbe("write");
        var cleanup = new IllegalArgumentException("completion failed");
        doThrow(cleanup).when(f.async).complete();
        assertSame(f.output.fatal, assertThrows(FatalProbe.class, f::resume));
        assertArrayEquals(new Throwable[]{cleanup}, f.output.fatal.getSuppressed());
        assertEquals(1, f.completed.get());
    }

    @Test void FatalCompletionCallbackIsNotHiddenByAnotherCompletionFailure() throws Exception {
        var async = mock(AsyncContext.class);
        var response = mock(HttpServletResponse.class);
        var output = new ControlledOutput();
        var fatal = new FatalProbe("callback");
        var cleanup = new IllegalArgumentException("async.complete");
        when(response.getOutputStream()).thenReturn(output);
        doThrow(cleanup).when(async).complete();
        CachedResponseWriter.write(async, response, new byte[0], () -> { throw fatal; });
        output.ready = true;
        assertSame(fatal, assertThrows(FatalProbe.class, output.listener::onWritePossible));
        assertArrayEquals(new Throwable[]{cleanup}, fatal.getSuppressed());
        verify(async, times(1)).complete();
        output.recycled = true;
        assertDoesNotThrow(output.listener::onWritePossible);
    }

    private static final class FatalProbe extends VirtualMachineError {
        FatalProbe(String message) { super(message); }
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + 13);
        return bytes;
    }

    private static final class Fixture {
        final AsyncContext async = mock(AsyncContext.class);
        final ControlledOutput output = new ControlledOutput();
        final AtomicInteger completed = new AtomicInteger();
        AsyncListener lifecycle;

        Fixture(byte[] content) throws Exception {
            var response = mock(HttpServletResponse.class);
            when(response.getOutputStream()).thenReturn(output);
            doAnswer(invocation -> { lifecycle = invocation.getArgument(0); return null; })
                    .when(async).addListener(any(AsyncListener.class));
            doAnswer(invocation -> { lifecycle.onComplete(new AsyncEvent(async)); return null; })
                    .when(async).complete();
            CachedResponseWriter.write(async, response, content, completed::incrementAndGet);
        }

        void resume() throws IOException {
            output.ready = true;
            output.listener.onWritePossible();
        }

        void drain() throws IOException {
            for (int i = 0; i < 1024 && completed.get() == 0; i++) resume();
            assertEquals(1, completed.get(), "writer did not finish");
        }
    }

    private static final class ControlledOutput extends ServletOutputStream {
        final MessageDigest digest;
        WriteListener listener;
        boolean ready;
        boolean pauseFlush;
        boolean failWrite;
        boolean recycled;
        String fatalStage;
        Error fatal;
        long bytes;
        int writes;
        int flushes;
        int maxWrite;

        ControlledOutput() throws Exception { digest = MessageDigest.getInstance("SHA-256"); }
        private void active() { assertFalse(recycled, "access after Servlet recycle"); }
        @Override public boolean isReady() {
            active();
            if ("ready".equals(fatalStage)) throw fatal;
            return ready;
        }
        @Override public void setWriteListener(WriteListener value) { active(); listener = value; }
        @Override public void write(int value) { fail("writer must offer bounded byte array slices"); }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            active();
            assertTrue(ready, "write while not ready");
            if ("write".equals(fatalStage)) throw fatal;
            if (failWrite) throw new IOException("write failed");
            digest.update(value, offset, length);
            bytes += length;
            writes++;
            maxWrite = Math.max(maxWrite, length);
            ready = false;
        }
        @Override public void flush() {
            active();
            assertTrue(ready, "flush while not ready");
            if ("flush".equals(fatalStage)) throw fatal;
            flushes++;
            if (pauseFlush) ready = false;
        }
    }
}
