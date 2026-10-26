package com.otr.portal.proxy.caching;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A streaming response tee with genuine Servlet nonblocking readiness and bounded capture.
 * Overflow, cancellation or a write error disables caching, never delivery of the response.
 * Call {@link #finishCapture(boolean)} only after a positively completed exchange; closing or
 * flushing the output is not evidence that the exchange succeeded. The terminal snapshot reads
 * only locally owned state, so it is also safe after the container has recycled the response.
 *
 * The shared budget reserves twice the body limit for segments plus the final byte[] snapshot.
 * It bounds payload allocation, not JVM RSS; bounded header metadata and retained cache entries
 * have separate limits. The application must also bound its completed-response cache.
 */
public class ContentCachingResponse extends HttpServletResponseWrapper {
    public static final int DEFAULT_MAX_CAPTURE_BYTES = 8 * 1024 * 1024;
    private static final int SEGMENT_BYTES = 32 * 1024;
    private static final int MAX_HEADER_CHARACTERS = 32 * 1024;
    private static final int MAX_HEADER_VALUES = 256;
    private static final CaptureBudget DEFAULT_BUDGET = new CaptureBudget(128L * 1024 * 1024);

    private final int maxCaptureBytes;
    private final CaptureBudget budget;
    private final List<byte[]> segments = new ArrayList<>();
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private int segmentOffset;
    private int contentSize;
    private int statusCode;
    private long declaredLength = -1;
    private long reservedBytes;
    private boolean discarded;
    private boolean finished;
    private CachedResponse completed;
    private ServletOutputStream outputStream;
    private PrintWriter writer;
    private String writerEncoding;
    private boolean streamSelected;

    public ContentCachingResponse(HttpServletResponse response) {
        this(response, DEFAULT_MAX_CAPTURE_BYTES, DEFAULT_BUDGET);
    }

    public ContentCachingResponse(HttpServletResponse response, int maxCaptureBytes) {
        this(response, maxCaptureBytes, DEFAULT_BUDGET);
    }

    public ContentCachingResponse(HttpServletResponse response, int maxCaptureBytes, CaptureBudget budget) {
        super(response);
        if (maxCaptureBytes < 0) throw new IllegalArgumentException("maxCaptureBytes must be nonnegative");
        this.maxCaptureBytes = maxCaptureBytes;
        this.budget = Objects.requireNonNull(budget, "budget");
        statusCode = response.getStatus();
        for (String name : response.getHeaderNames()) refreshHeader(name);
    }

    /** Shared across concurrent misses. Reservation is lazy, before the first body allocation. */
    public static final class CaptureBudget {
        private final long maxBytes;
        private final AtomicLong reserved = new AtomicLong();

        public CaptureBudget(long maxBytes) {
            if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must be nonnegative");
            this.maxBytes = maxBytes;
        }

        public long maxBytes() { return maxBytes; }
        public long reservedBytes() { return reserved.get(); }
        public long availableBytes() { return maxBytes - reserved.get(); }

        private boolean reserve(long bytes) {
            long current;
            do {
                current = reserved.get();
                if (bytes > maxBytes - current) return false;
            } while (!reserved.compareAndSet(current, current + bytes));
            return true;
        }

        private void release(long bytes) { reserved.addAndGet(-bytes); }
    }

    /**
     * Completes capture once. HTTP errors, partial writes and every discarded capture return empty.
     * No Servlet methods, writer flushes or network operations are performed here.
     */
    public synchronized Optional<CachedResponse> finishCapture(boolean successful) {
        if (finished) return Optional.ofNullable(completed);
        finished = true;
        try {
            if (!successful || discarded || statusCode != SC_OK
                    || (declaredLength >= 0 && declaredLength != contentSize)) return Optional.empty();
            byte[] bytes = new byte[contentSize];
            int copied = 0;
            for (byte[] segment : segments) {
                int count = Math.min(segment.length, contentSize - copied);
                System.arraycopy(segment, 0, bytes, copied, count);
                copied += count;
            }
            var snapshot = new CachedResponse();
            headers.forEach((name, values) -> values.forEach(value -> snapshot.addHeader(name, value)));
            snapshot.setStatus(statusCode);
            snapshot.setContent(bytes);
            completed = snapshot;
            return Optional.of(snapshot);
        } finally {
            clearSegments();
            headers.clear();
            releaseReservation();
        }
    }

    /** Permanently bypasses this response's capture, including after reset; streaming continues. */
    public synchronized void discardCapture() {
        if (finished) return;
        discarded = true;
        clearSegments();
        headers.clear();
        releaseReservation();
    }

    public synchronized boolean isCaptureEnabled() { return !discarded && !finished; }

    /** A partial capture is never exposed as a complete cache entry. */
    public synchronized CachedResponse toCachedResponse() {
        if (completed == null) throw new IllegalStateException("No successfully completed cache entry");
        return completed;
    }

    public synchronized byte[] getContentAsByteArray() { return toCachedResponse().getContent(); }
    public synchronized InputStream getContentInputStream() {
        return new ByteArrayInputStream(toCachedResponse().getContent());
    }
    public synchronized int getContentSize() { return completed == null ? contentSize : completed.getContent().length; }
    public synchronized int getStatusCode() { return statusCode; }

    private void clearSegments() {
        segments.clear();
        contentSize = 0;
        segmentOffset = 0;
    }

    private void releaseReservation() {
        long release = reservedBytes;
        reservedBytes = 0;
        if (release != 0) budget.release(release);
    }

    private synchronized void capture(byte[] bytes, int offset, int length) {
        if (!prepareCapture(length)) return;
        while (length > 0) {
            if (segments.isEmpty() || segmentOffset == segments.getLast().length) {
                segments.add(new byte[Math.min(SEGMENT_BYTES, maxCaptureBytes - contentSize)]);
                segmentOffset = 0;
            }
            byte[] segment = segments.getLast();
            int count = Math.min(length, segment.length - segmentOffset);
            System.arraycopy(bytes, offset, segment, segmentOffset, count);
            offset += count;
            length -= count;
            segmentOffset += count;
            contentSize += count;
        }
    }

    private synchronized void capture(ByteBuffer bytes) {
        if (!prepareCapture(bytes.remaining())) return;
        while (bytes.hasRemaining()) {
            if (segments.isEmpty() || segmentOffset == segments.getLast().length) {
                segments.add(new byte[Math.min(SEGMENT_BYTES, maxCaptureBytes - contentSize)]);
                segmentOffset = 0;
            }
            byte[] segment = segments.getLast();
            int count = Math.min(bytes.remaining(), segment.length - segmentOffset);
            bytes.get(segment, segmentOffset, count);
            segmentOffset += count;
            contentSize += count;
        }
    }

    /** Caller holds this response's capture monitor; no downstream call is made under it. */
    private boolean prepareCapture(int length) {
        if (discarded || finished || length == 0) return false;
        if (length > maxCaptureBytes - contentSize) { discardCapture(); return false; }
        if (reservedBytes == 0) {
            long reservation = 2L * maxCaptureBytes;
            if (!budget.reserve(reservation)) { discardCapture(); return false; }
            reservedBytes = reservation;
        }
        return true;
    }

    private synchronized void refreshHeader(String name) {
        if (discarded || finished) return;
        var source = super.getHeaders(name);
        if (source.size() > MAX_HEADER_VALUES) { discardCapture(); return; }
        List<String> values = List.copyOf(source);
        if (values.isEmpty()) headers.remove(name);
        else headers.put(name, values);
        long characters = 0;
        int count = 0;
        for (var entry : headers.entrySet()) {
            for (String value : entry.getValue()) {
                characters += entry.getKey().length() + value.length();
                if (++count > MAX_HEADER_VALUES || characters > MAX_HEADER_CHARACTERS) {
                    discardCapture();
                    return;
                }
            }
        }
        if (name.equalsIgnoreCase("Content-Length")) {
            declaredLength = -1;
            for (String value : values) {
                try {
                    long parsed = Long.parseLong(value.trim());
                    if (parsed < 0 || parsed > maxCaptureBytes
                            || (declaredLength >= 0 && parsed != declaredLength)) {
                        discardCapture();
                        return;
                    }
                    declaredLength = parsed;
                } catch (NumberFormatException invalid) { discardCapture(); return; }
            }
        }
    }

    @Override public void setHeader(String name, String value) {
        super.setHeader(name, value);
        refreshHeader(name);
    }
    @Override public void addHeader(String name, String value) {
        super.addHeader(name, value);
        refreshHeader(name);
    }
    @Override public void setDateHeader(String name, long value) {
        super.setDateHeader(name, value);
        refreshHeader(name);
    }
    @Override public void addDateHeader(String name, long value) {
        super.addDateHeader(name, value);
        refreshHeader(name);
    }
    @Override public void setIntHeader(String name, int value) {
        super.setIntHeader(name, value);
        refreshHeader(name);
    }
    @Override public void addIntHeader(String name, int value) {
        super.addIntHeader(name, value);
        refreshHeader(name);
    }
    @Override public void addCookie(Cookie cookie) {
        super.addCookie(cookie);
        refreshHeader("Set-Cookie");
    }
    @Override public void setContentLength(int length) {
        super.setContentLength(length);
        refreshHeader("Content-Length");
    }
    @Override public void setContentLengthLong(long length) {
        super.setContentLengthLong(length);
        refreshHeader("Content-Length");
    }
    @Override public void setContentType(String type) {
        super.setContentType(type);
        if (writerEncoding != null) super.setCharacterEncoding(writerEncoding);
        refreshHeader("Content-Type");
    }
    @Override public void setCharacterEncoding(String encoding) {
        if (writerEncoding == null) super.setCharacterEncoding(encoding);
        refreshHeader("Content-Type");
    }
    @Override public void setCharacterEncoding(Charset encoding) {
        setCharacterEncoding(encoding == null ? null : encoding.name());
    }
    @Override public void setLocale(Locale locale) {
        super.setLocale(locale);
        if (writerEncoding != null) super.setCharacterEncoding(writerEncoding);
        refreshHeader("Content-Type");
        refreshHeader("Content-Language");
    }
    @Override public void setStatus(int status) {
        super.setStatus(status);
        synchronized (this) { statusCode = super.getStatus(); }
    }
    @Override public void sendError(int status) throws IOException {
        discardCapture();
        super.sendError(status);
        synchronized (this) { statusCode = status; }
    }
    @Override public void sendError(int status, String message) throws IOException {
        discardCapture();
        super.sendError(status, message);
        synchronized (this) { statusCode = status; }
    }
    @Override public void sendRedirect(String location) throws IOException {
        discardCapture();
        super.sendRedirect(location);
        synchronized (this) { statusCode = super.getStatus(); }
    }
    @Override public void sendRedirect(String location, int status) throws IOException {
        discardCapture();
        super.sendRedirect(location, status);
        synchronized (this) { statusCode = super.getStatus(); }
    }
    @Override public void sendRedirect(String location, boolean clearBuffer) throws IOException {
        discardCapture();
        super.sendRedirect(location, clearBuffer);
        synchronized (this) { statusCode = super.getStatus(); }
    }
    @Override public void sendRedirect(String location, int status, boolean clearBuffer) throws IOException {
        discardCapture();
        super.sendRedirect(location, status, clearBuffer);
        synchronized (this) { statusCode = super.getStatus(); }
    }

    @Override public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) throw new IllegalStateException("getWriter() already selected");
        streamSelected = true;
        return captureStream();
    }

    private ServletOutputStream captureStream() throws IOException {
        if (outputStream == null) outputStream = new ResponseServletOutputStream(super.getOutputStream());
        return outputStream;
    }

    @Override public PrintWriter getWriter() throws IOException {
        if (streamSelected) throw new IllegalStateException("getOutputStream() already selected");
        if (writer == null) {
            writerEncoding = Optional.ofNullable(getCharacterEncoding()).orElse(StandardCharsets.ISO_8859_1.name());
            super.setCharacterEncoding(writerEncoding);
            refreshHeader("Content-Type");
            writer = new PrintWriter(new OutputStreamWriter(captureStream(), Charset.forName(writerEncoding))) {
                @Override public void write(int value) { super.write(value); super.flush(); }
                @Override public void write(char[] value, int offset, int length) {
                    super.write(value, offset, length); super.flush();
                }
                @Override public void write(String value, int offset, int length) {
                    super.write(value, offset, length); super.flush();
                }
            };
        }
        return writer;
    }

    @Override public void flushBuffer() throws IOException {
        if (writer != null) writer.flush();
        try { super.flushBuffer(); }
        catch (IOException | RuntimeException | Error failure) { discardCapture(); throw failure; }
    }

    @Override public void resetBuffer() {
        super.resetBuffer();
        synchronized (this) {
            clearSegments();
            releaseReservation();
        }
    }

    @Override public void reset() {
        super.reset();
        synchronized (this) {
            clearSegments();
            releaseReservation();
            headers.clear();
            declaredLength = -1;
            statusCode = super.getStatus();
        }
        outputStream = null;
        writer = null;
        writerEncoding = null;
        streamSelected = false;
    }

    private final class ResponseServletOutputStream extends ServletOutputStream {
        private final ServletOutputStream delegate;
        private final byte[] single = new byte[1];

        private ResponseServletOutputStream(ServletOutputStream delegate) { this.delegate = delegate; }

        @Override public void write(int value) throws IOException {
            try {
                delegate.write(value);
                single[0] = (byte) value;
                capture(single, 0, 1);
            } catch (IOException | RuntimeException | Error failure) { discardCapture(); throw failure; }
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            try {
                delegate.write(bytes, offset, length);
                capture(bytes, offset, length);
            } catch (IOException | RuntimeException | Error failure) { discardCapture(); throw failure; }
        }

        @Override public void write(ByteBuffer bytes) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            ByteBuffer captured = bytes.asReadOnlyBuffer();
            try {
                delegate.write(bytes);
                capture(captured);
            } catch (IOException | RuntimeException | Error failure) { discardCapture(); throw failure; }
        }

        @Override public boolean isReady() {
            try { return delegate.isReady(); }
            catch (RuntimeException | Error failure) { discardCapture(); throw failure; }
        }

        @Override public void setWriteListener(WriteListener listener) {
            Objects.requireNonNull(listener, "listener");
            try {
                delegate.setWriteListener(new WriteListener() {
                    @Override public void onWritePossible() throws IOException {
                        try { listener.onWritePossible(); }
                        catch (IOException | RuntimeException | Error failure) { discardCapture(); throw failure; }
                    }
                    @Override public void onError(Throwable failure) {
                        discardCapture();
                        listener.onError(failure);
                    }
                });
            } catch (RuntimeException | Error failure) { discardCapture(); throw failure; }
        }

        @Override public void flush() throws IOException {
            try { delegate.flush(); }
            catch (IOException | RuntimeException | Error failure) { discardCapture(); throw failure; }
        }

        @Override public void close() throws IOException {
            try { delegate.close(); }
            catch (IOException | RuntimeException | Error failure) { discardCapture(); throw failure; }
        }
    }
}
