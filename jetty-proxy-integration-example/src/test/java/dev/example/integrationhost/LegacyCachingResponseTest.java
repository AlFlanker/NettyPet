package dev.example.integrationhost;

import com.otr.portal.proxy.caching.ContentCachingResponse;
import com.otr.portal.proxy.caching.ContentCachingResponse.CaptureBudget;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Compiles and exercises the actual portal example wrapper, not a test copy. */
class LegacyCachingResponseTest {
    @Test
    void readinessAndWriteNotificationsComeFromTheActualServletStream() throws Exception {
        var response = new Response();
        var budget = new CaptureBudget(32);
        var capture = new ContentCachingResponse(response, 16, budget);
        ServletOutputStream output = capture.getOutputStream();
        AtomicInteger writable = new AtomicInteger();
        AtomicBoolean errorObserved = new AtomicBoolean();
        output.setWriteListener(new WriteListener() {
            @Override public void onWritePossible() { writable.incrementAndGet(); }
            @Override public void onError(Throwable failure) {
                assertFalse(capture.isCaptureEnabled());
                assertEquals(0, capture.getContentSize());
                assertEquals(0, budget.reservedBytes());
                errorObserved.set(true);
            }
        });
        assertNotNull(response.stream.listener);
        response.stream.ready = false;
        assertFalse(output.isReady());
        response.stream.ready = true;
        response.stream.listener.onWritePossible();
        assertEquals(1, writable.get());
        assertTrue(output.isReady());
        output.write(new byte[]{1, 2, 3});
        assertEquals(32, budget.reservedBytes());
        response.stream.listener.onError(new IOException("client disconnected"));
        assertTrue(errorObserved.get());
        assertTrue(capture.finishCapture(true).isEmpty());
    }

    @Test
    void onlyPositiveCompletionPublishesFullBodyAndReleasesReservationOnce() throws Exception {
        var response = new Response();
        var budget = new CaptureBudget(32);
        var capture = new ContentCachingResponse(response, 16, budget);
        capture.getOutputStream().write(new byte[]{1, 2, 3});
        capture.getOutputStream().close();
        assertThrows(IllegalStateException.class, capture::toCachedResponse);
        assertEquals(32, budget.reservedBytes());
        var entry = capture.finishCapture(true).orElseThrow();
        assertArrayEquals(new byte[]{1, 2, 3}, entry.getContent());
        assertSame(entry, capture.finishCapture(true).orElseThrow());
        capture.discardCapture();
        assertEquals(0, budget.reservedBytes());
        assertSame(entry, capture.toCachedResponse());
    }

    @Test
    void unsuccessfulExchangeNeverPublishesAcceptedPrefix() throws Exception {
        var response = new Response();
        var budget = new CaptureBudget(32);
        var capture = new ContentCachingResponse(response, 16, budget);
        capture.getOutputStream().write(new byte[]{1, 2, 3});
        assertTrue(capture.finishCapture(false).isEmpty());
        assertTrue(capture.finishCapture(true).isEmpty());
        assertThrows(IllegalStateException.class, capture::getContentAsByteArray);
        assertEquals(0, budget.reservedBytes());
    }

    @Test
    void overflowBypassesCacheButDeliversTheWholeResponse() throws Exception {
        var response = new Response();
        var budget = new CaptureBudget(8);
        var capture = new ContentCachingResponse(response, 4, budget);
        capture.getOutputStream().write(new byte[]{1, 2, 3});
        capture.getOutputStream().write(new byte[]{4, 5, 6});
        capture.getOutputStream().write(new byte[]{7, 8, 9});
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, response.stream.bytes.toByteArray());
        assertFalse(capture.isCaptureEnabled());
        assertEquals(0, capture.getContentSize());
        assertEquals(0, budget.reservedBytes());
        assertTrue(capture.finishCapture(true).isEmpty());
    }

    @Test
    void exhaustedSharedBudgetBypassesOnlyNewCapturesAndRecoversAfterCleanup() throws Exception {
        var budget = new CaptureBudget(16);
        var first = new ContentCachingResponse(new Response(), 8, budget);
        var secondResponse = new Response();
        var second = new ContentCachingResponse(secondResponse, 8, budget);
        first.getOutputStream().write(1);
        second.getOutputStream().write(2);
        assertEquals(16, budget.reservedBytes());
        assertFalse(second.isCaptureEnabled());
        assertArrayEquals(new byte[]{2}, secondResponse.stream.bytes.toByteArray());
        first.discardCapture();
        assertEquals(0, budget.reservedBytes());
        var third = new ContentCachingResponse(new Response(), 8, budget);
        third.getOutputStream().write(3);
        assertTrue(third.finishCapture(true).isPresent());
        assertEquals(0, budget.reservedBytes());
        assertTrue(second.finishCapture(true).isEmpty());
    }

    @Test
    void declaredLargeLengthIsForwardedWithoutCaptureAllocation() throws Exception {
        for (int setter = 0; setter < 3; setter++) {
            // Spring's mock parses setHeader(Content-Length) as int, unlike the Servlet long contract.
            HttpServletResponse response = mock(HttpServletResponse.class);
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Output stream = new Output();
            when(response.getStatus()).thenReturn(200);
            when(response.getHeaderNames()).thenAnswer(ignored -> headers.keySet());
            when(response.getHeaders(anyString())).thenAnswer(call -> headers.getOrDefault(call.getArgument(0), List.of()));
            when(response.getOutputStream()).thenReturn(stream);
            doAnswer(call -> { headers.put("Content-Length", List.of(Long.toString(call.<Long>getArgument(0)))); return null; })
                    .when(response).setContentLengthLong(anyLong());
            doAnswer(call -> { headers.put(call.getArgument(0), List.of(call.<String>getArgument(1))); return null; })
                    .when(response).setHeader(anyString(), anyString());
            doAnswer(call -> { headers.put(call.getArgument(0), List.of(call.<String>getArgument(1))); return null; })
                    .when(response).addHeader(anyString(), anyString());
            var budget = new CaptureBudget(16);
            var capture = new ContentCachingResponse(response, 8, budget);
            long length = 4L * 1024 * 1024 * 1024;
            if (setter == 0) capture.setContentLengthLong(length);
            if (setter == 1) capture.setHeader("Content-Length", Long.toString(length));
            if (setter == 2) capture.addHeader("content-length", Long.toString(length));
            assertEquals(List.of(Long.toString(length)), headers.get("Content-Length"));
            capture.getOutputStream().write(new byte[]{1, 2, 3});
            assertEquals(0, budget.reservedBytes());
            assertFalse(capture.isCaptureEnabled());
            assertTrue(capture.finishCapture(true).isEmpty());
        }
    }

    @Test
    void writeFailureAndReentrantAsyncErrorInvalidateCaptureBeforeCompletion() throws Exception {
        for (boolean asynchronous : new boolean[]{false, true}) {
            var response = new Response();
            var budget = new CaptureBudget(32);
            var capture = new ContentCachingResponse(response, 16, budget);
            var output = capture.getOutputStream();
            output.setWriteListener(new WriteListener() {
                @Override public void onWritePossible() { }
                @Override public void onError(Throwable failure) { }
            });
            output.write(new byte[]{1, 2});
            if (asynchronous) {
                response.stream.errorInsideWrite = true;
                output.write(new byte[]{3, 4});
            } else {
                response.stream.failWrite = true;
                assertThrows(IOException.class, () -> output.write(new byte[]{3, 4}));
            }
            assertEquals(0, budget.reservedBytes());
            assertTrue(capture.finishCapture(true).isEmpty());
        }
    }

    @Test
    void snapshotPreservesInitialHeadersReplacementAndRepeatedCookies() throws Exception {
        var response = new Response();
        response.addHeader("X-Initial", "initial");
        var capture = new ContentCachingResponse(response, 16, new CaptureBudget(32));
        capture.addHeader("X-Test", "a");
        capture.addHeader("x-test", "b");
        capture.setHeader("X-Test", "replacement");
        capture.addHeader("Set-Cookie", "a=1; Path=/");
        capture.addHeader("set-cookie", "b=2; Path=/");
        capture.addCookie(new Cookie("c", "3"));
        capture.setIntHeader("X-Number", 7);
        capture.setContentType("text/plain");
        capture.setContentLength(3);
        capture.getOutputStream().write(new byte[]{1, 2, 3});
        var entry = capture.finishCapture(true).orElseThrow();
        assertEquals(List.of("replacement"), List.copyOf(entry.getHeaders().get("x-test")));
        assertEquals(List.of("initial"), List.copyOf(entry.getHeaders().get("X-INITIAL")));
        assertEquals(List.copyOf(response.getHeaders("Set-Cookie")), List.copyOf(entry.getHeaders().get("SET-cookie")));
        assertEquals(3, entry.getHeaders().get("Set-Cookie").size());
        assertEquals(List.of("3"), List.copyOf(entry.getHeaders().get("Content-Length")));
        assertEquals(List.of("7"), List.copyOf(entry.getHeaders().get("X-Number")));
        assertEquals(List.of("text/plain"), List.copyOf(entry.getHeaders().get("Content-Type")));
    }

    @Test
    void resetBufferClearsBothBodiesAndResetAlsoClearsMetadata() throws Exception {
        var response = new Response();
        var budget = new CaptureBudget(32);
        var capture = new ContentCachingResponse(response, 16, budget);
        capture.setHeader("X-Keep", "yes");
        capture.getOutputStream().write(new byte[]{1, 2});
        capture.resetBuffer();
        assertEquals(0, response.stream.bytes.size());
        assertEquals(0, capture.getContentSize());
        assertEquals(0, budget.reservedBytes());
        capture.getOutputStream().write(new byte[]{3, 4});
        capture.setStatus(201);
        capture.reset();
        assertNull(response.getHeader("X-Keep"));
        assertEquals(200, capture.getStatusCode());
        assertEquals(0, budget.reservedBytes());
        capture.getOutputStream().write(5);
        var entry = capture.finishCapture(true).orElseThrow();
        assertArrayEquals(new byte[]{5}, entry.getContent());
        assertTrue(entry.getHeaders().isEmpty());
    }

    @Test
    void flushAndBufferSizeRetainServletSemanticsWithoutAllocatingCapture() throws Exception {
        var response = new Response();
        var budget = new CaptureBudget(32);
        var capture = new ContentCachingResponse(response, 16, budget);
        capture.setBufferSize(1024);
        assertEquals(1024, response.getBufferSize());
        assertEquals(0, budget.reservedBytes());
        capture.getOutputStream().flush();
        assertEquals(1, response.stream.flushes);
        capture.flushBuffer();
        assertTrue(response.isCommitted());
        assertThrows(IllegalStateException.class, capture::resetBuffer);
        capture.discardCapture();
    }

    @Test
    void writerUsesSelectedEncodingAndCannotBeMixedWithOutputStream() throws Exception {
        var response = new Response();
        var budget = new CaptureBudget(128);
        var capture = new ContentCachingResponse(response, 64, budget);
        capture.setCharacterEncoding("UTF-8");
        capture.getWriter().write("Привет");
        capture.setCharacterEncoding("ISO-8859-1");
        capture.setCharacterEncoding(StandardCharsets.US_ASCII);
        capture.getWriter().flush();
        assertEquals("UTF-8", response.getCharacterEncoding());
        assertThrows(IllegalStateException.class, capture::getOutputStream);
        assertArrayEquals("Привет".getBytes(StandardCharsets.UTF_8), capture.finishCapture(true).orElseThrow().getContent());
        var other = new ContentCachingResponse(new Response(), 16, budget);
        other.getOutputStream();
        assertThrows(IllegalStateException.class, other::getWriter);
        other.discardCapture();
    }

    @Test
    void statusErrorsRedirectAndOversizedMetadataBypassCache() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            var response = new Response();
            var budget = new CaptureBudget(32);
            var capture = new ContentCachingResponse(response, 16, budget);
            capture.getOutputStream().write(1);
            if (mode == 0) capture.setStatus(206);
            if (mode == 1) capture.sendError(500);
            if (mode == 2) capture.sendRedirect("/login", 302, true);
            if (mode == 3) capture.setHeader("X-Large", "x".repeat(33 * 1024));
            assertTrue(capture.finishCapture(true).isEmpty());
            assertEquals(0, budget.reservedBytes());
        }
    }

    @Test
    void declaredLengthMismatchCannotPublishEvenIfLegacyCommandReportsSuccess() throws Exception {
        for (int expected : new int[]{2, 4}) {
            var budget = new CaptureBudget(16);
            var capture = new ContentCachingResponse(new Response(), 8, budget);
            capture.setContentLength(expected);
            capture.getOutputStream().write(new byte[]{1, 2, 3});
            assertTrue(capture.finishCapture(true).isEmpty());
            assertEquals(0, budget.reservedBytes());
        }
        var response = new Response();
        var capture = new ContentCachingResponse(response, 8, new CaptureBudget(16));
        capture.setContentLength(4);
        capture.reset();
        capture.setContentLengthLong(3);
        capture.getOutputStream().write(new byte[]{1, 2, 3});
        assertTrue(capture.finishCapture(true).isPresent());
    }

    @Test
    void byteBufferWriteDelegatesWithoutTheServletDefaultFullHeapCopy() throws Exception {
        var response = new Response();
        var budget = new CaptureBudget(32);
        var capture = new ContentCachingResponse(response, 16, budget);
        ByteBuffer bytes = ByteBuffer.allocateDirect(5).put(new byte[]{1, 2, 3, 4, 5}).flip();
        bytes.position(1);
        bytes.limit(4);
        capture.getOutputStream().write(bytes);
        assertEquals(1, response.stream.byteBufferWrites);
        assertEquals(4, bytes.position());
        assertArrayEquals(new byte[]{2, 3, 4}, capture.finishCapture(true).orElseThrow().getContent());
        assertArrayEquals(new byte[]{2, 3, 4}, response.stream.bytes.toByteArray());
        assertEquals(0, budget.reservedBytes());
    }

    @Test
    void exactSegmentBoundaryAndEmptyResponsesCaptureCorrectly() throws Exception {
        byte[] body = new byte[3 * 32 * 1024 + 7];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 31);
        var budget = new CaptureBudget(2L * body.length);
        var capture = new ContentCachingResponse(new Response(), body.length, budget);
        capture.getOutputStream().write(body, 0, 100);
        capture.getOutputStream().write(body, 100, body.length - 100);
        assertArrayEquals(body, capture.finishCapture(true).orElseThrow().getContent());
        assertEquals(0, budget.reservedBytes());
        var empty = new ContentCachingResponse(new Response(), 0, new CaptureBudget(0));
        assertArrayEquals(new byte[0], empty.finishCapture(true).orElseThrow().getContent());
    }

    private static final class Response extends MockHttpServletResponse {
        final Output stream = new Output();
        @Override public ServletOutputStream getOutputStream() { return stream; }
        @Override public void resetBuffer() { super.resetBuffer(); stream.bytes.reset(); }
        @Override public void reset() { super.reset(); stream.bytes.reset(); }
    }

    private static final class Output extends ServletOutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean ready = true;
        boolean failWrite;
        boolean errorInsideWrite;
        int flushes;
        int byteBufferWrites;
        WriteListener listener;
        @Override public boolean isReady() { return ready; }
        @Override public void setWriteListener(WriteListener listener) { this.listener = listener; }
        @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            if (failWrite) throw new IOException("write failed");
            bytes.write(value, offset, length);
            if (errorInsideWrite) listener.onError(new IOException("reentrant error"));
        }
        @Override public void write(ByteBuffer value) {
            byteBufferWrites++;
            while (value.hasRemaining()) bytes.write(value.get());
        }
        @Override public void flush() { flushes++; }
    }
}
