package dev.example.jettyclient.proxy;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises Servlet wrappers that report a write error synchronously from an I/O call. */
class DownstreamPortableReentrancyTest {
    @Test
    void readinessErrorMustFailTheWriteWithoutWritingOrFlushingAfterTheError() {
        Fixture fixture = new Fixture(ErrorAt.READINESS, false);
        Completion completion = new Completion();

        fixture.downstream.write(ByteBuffer.wrap(new byte[]{1, 2, 3}), completion);
        fixture.drain();

        completion.assertFailedOnce(fixture.output.failure);
        assertEquals(0, fixture.output.writeCalls, "An error reported by isReady must prevent the write");
        assertEquals(0, fixture.output.flushCalls, "An error reported by isReady must prevent flush");
        assertEquals(0, fixture.output.ioCallsAfterError);
    }

    @Test
    void errorReportedByWriteMustPreventFlushAndSuccessfulCompletion() {
        Fixture fixture = new Fixture(ErrorAt.WRITE, false);
        Completion completion = new Completion();

        fixture.downstream.write(ByteBuffer.wrap(new byte[]{4, 5, 6}), completion);
        fixture.drain();

        completion.assertFailedOnce(fixture.output.failure);
        assertEquals(1, fixture.output.writeCalls, "The failing write itself may have consumed bytes");
        assertEquals(0, fixture.output.flushCalls, "No flush may follow a write error");
        assertEquals(0, fixture.output.ioCallsAfterError);
    }

    @Test
    void errorReportedByFlushMustNotTurnIntoSuccessfulCompletion() {
        Fixture fixture = new Fixture(ErrorAt.FLUSH, false);
        Completion completion = new Completion();

        fixture.downstream.write(ByteBuffer.wrap(new byte[]{7, 8, 9}), completion);
        fixture.drain();

        completion.assertFailedOnce(fixture.output.failure);
        assertEquals(1, fixture.output.writeCalls);
        assertEquals(1, fixture.output.flushCalls, "The flush which reports the error is allowed");
        assertEquals(0, fixture.output.ioCallsAfterError);
    }

    @Test
    void cancellationOfPendingArrayWriteKeepsTheContainersArrayStableAndCompletesOnlyOnce() throws IOException {
        Fixture fixture = new Fixture(ErrorAt.NONE, true);
        byte[] payload = new byte[]{10, 11, 12, 13};
        byte[] expected = payload.clone();
        Completion completion = new Completion();
        fixture.downstream.write(ByteBuffer.wrap(payload), completion);
        assertEquals(0, completion.successes + completion.failures,
                "The output has not returned ownership of its pending array");
        byte[] containerArray = fixture.output.pendingArray;
        assertNotNull(containerArray);

        fixture.output.signalError();
        fixture.drain();
        completion.assertFailedOnce(fixture.output.failure);
        Arrays.fill(payload, (byte)99);

        Completion rejected = new Completion();
        fixture.downstream.write(ByteBuffer.wrap(new byte[]{20, 21, 22, 23}), rejected);
        fixture.output.listener.onWritePossible();
        fixture.output.listener.onError(fixture.output.failure);
        fixture.drain();

        assertEquals(0, rejected.successes);
        assertEquals(1, rejected.failures, "A cancelled downstream must reject another write");
        completion.assertFailedOnce(fixture.output.failure);
        assertArrayEquals(expected, Arrays.copyOf(containerArray, expected.length),
                "Cancellation must not recycle or overwrite the array still retained by the container");
        assertEquals(1, fixture.output.writeCalls);
        assertEquals(0, fixture.output.flushCalls);
        assertEquals(0, fixture.output.ioCallsAfterError);
    }

    private enum ErrorAt { NONE, READINESS, WRITE, FLUSH }

    private static final class Fixture {
        final Deque<Runnable> events = new ArrayDeque<>();
        final Output output;
        final Downstream.Portable downstream;

        Fixture(ErrorAt errorAt, boolean blockAfterWrite) {
            output = new Output(errorAt, blockAfterWrite);
            downstream = new Downstream.Portable(output, 1024, events::addLast, ignored -> { });
        }

        void drain() {
            int count = 0;
            Runnable event;
            while ((event = events.pollFirst()) != null) {
                assertTrue(++count < 100, "Callbacks must make progress without an unbounded notification loop");
                event.run();
            }
        }
    }

    private static final class Output extends ServletOutputStream {
        final IOException failure = new IOException("Servlet wrapper reported an I/O failure");
        final ErrorAt errorAt;
        final boolean blockAfterWrite;
        WriteListener listener;
        byte[] pendingArray;
        boolean errorReported;
        boolean blocked;
        int writeCalls;
        int flushCalls;
        int ioCallsAfterError;

        Output(ErrorAt errorAt, boolean blockAfterWrite) {
            this.errorAt = errorAt;
            this.blockAfterWrite = blockAfterWrite;
        }

        @Override public boolean isReady() {
            if (errorAt == ErrorAt.READINESS) signalError();
            // The readiness observation and an error notification may race or reenter.
            // Reporting the error does not magically invalidate an already computed true value.
            return !blocked;
        }

        @Override public void setWriteListener(WriteListener listener) { this.listener = listener; }

        @Override public void write(int value) { throw new AssertionError("A bulk write was expected"); }

        @Override public void write(byte[] bytes, int offset, int length) {
            if (errorReported) ioCallsAfterError++;
            writeCalls++;
            assertEquals(0, offset);
            assertTrue(length > 0);
            pendingArray = bytes;
            if (errorAt == ErrorAt.WRITE) signalError();
            if (blockAfterWrite) blocked = true;
        }

        @Override public void flush() {
            if (errorReported) ioCallsAfterError++;
            flushCalls++;
            if (errorAt == ErrorAt.FLUSH) signalError();
        }

        void signalError() {
            if (errorReported) return;
            errorReported = true;
            listener.onError(failure);
        }
    }

    private static final class Completion implements Callback {
        int successes;
        int failures;
        Throwable failure;

        @Override public void succeeded() { successes++; }
        @Override public void failed(Throwable cause) { failures++; failure = cause; }

        void assertFailedOnce(Throwable expected) {
            assertEquals(0, successes, "A reported Servlet I/O error must not be acknowledged as success");
            assertEquals(1, failures, "The pending write must fail exactly once");
            assertSame(expected, failure);
        }
    }
}
