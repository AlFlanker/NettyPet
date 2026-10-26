package com.otr.portal.proxy.caching;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.apache.commons.io.output.TeeOutputStream;
import org.springframework.util.FastByteArrayOutputStream;
import org.springframework.web.util.WebUtils;

import java.io.*;

/**
 * @author pobedenniy.alexey
 * @since 23.04.18
 */
public class ContentCachingResponse extends HttpServletResponseWrapper {

    private final FastByteArrayOutputStream content = new FastByteArrayOutputStream(1024);

    private ServletOutputStream outputStream;

    private PrintWriter writer;

    private int statusCode = HttpServletResponse.SC_OK;


    private CachedResponse cachedResponse = new CachedResponse();

    public ContentCachingResponse(HttpServletResponse resp) {
        super(resp);
    }

    @Override
    public void addHeader(String name, String value) {
        super.addHeader(name, value);
        cachedResponse.addHeader(name, value);
    }

    public CachedResponse toCachedResponse() {
        cachedResponse.setContent(content.toByteArray());
        cachedResponse.setStatus(statusCode);
        return cachedResponse;
    }

    @Override
    public void setStatus(int sc) {
        super.setStatus(sc);
        this.statusCode = sc;
    }

    @Override
    public void sendError(int sc) throws IOException {
        try {
            super.sendError(sc);
        } catch (IllegalStateException ex) {
            // Possibly on Tomcat when called too late: fall back to silent setStatus
            super.setStatus(sc);
        }
        this.statusCode = sc;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void sendError(int sc, String msg) throws IOException {
        try {
            super.sendError(sc, msg);
        } catch (IllegalStateException ex) {
            // Possibly on Tomcat when called too late: fall back to silent setStatus
            super.setStatus(sc);
        }
        this.statusCode = sc;
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        super.sendRedirect(location);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (this.outputStream == null) {
            this.outputStream = new ResponseServletOutputStream(super.getOutputStream(), content);
        }
        return this.outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (this.writer == null) {
            String characterEncoding = getCharacterEncoding();
            this.writer = characterEncoding != null ? new ResponsePrintWriter(characterEncoding, getOutputStream()) : new ResponsePrintWriter(WebUtils.DEFAULT_CHARACTER_ENCODING, getOutputStream());
        }
        return this.writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        // do not flush the underlying response as the content as not been copied to it yet
    }

    @Override
    public void setContentLength(int len) {
        if (len > this.content.size()) {
            this.content.resize(len);
        }
    }

    // Overrides Servlet 3.1 setContentLengthLong(long) at runtime
    public void setContentLengthLong(long len) {
        if (len > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Content-Length exceeds ContentCachingResponseWrapper's maximum (" +
                    Integer.MAX_VALUE + "): " + len);
        }
        int lenInt = (int) len;
        if (lenInt > this.content.size()) {
            this.content.resize(lenInt);
        }
    }

    @Override
    public void setBufferSize(int size) {
        if (size > this.content.size()) {
            this.content.resize(size);
        }
    }

    @Override
    public void resetBuffer() {
        this.content.reset();
    }

    @Override
    public void reset() {
        super.reset();
        this.content.reset();
    }

    /**
     * Return the status code as specified on the response.
     */
    public int getStatusCode() {
        return this.statusCode;
    }

    /**
     * Return the cached response content as a byte array.
     */
    public byte[] getContentAsByteArray() {
        return this.content.toByteArray();
    }

    /**
     * Return an {@link InputStream} to the cached content.
     *
     * @since 4.2
     */
    public InputStream getContentInputStream() {
        return this.content.getInputStream();
    }

    /**
     * Return the current size of the cached content.
     *
     * @since 4.2
     */
    public int getContentSize() {
        return this.content.size();
    }

    private class ResponseServletOutputStream extends ServletOutputStream {
        private TeeOutputStream outputStream;

        ResponseServletOutputStream(ServletOutputStream outputStream, OutputStream content) {
            this.outputStream = new TeeOutputStream(outputStream, content);
        }

        @Override
        public void write(int b) throws IOException {
            outputStream.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            outputStream.write(b, off, len);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {

        }
    }


    private class ResponsePrintWriter extends PrintWriter {

        ResponsePrintWriter(String characterEncoding, ServletOutputStream outputStream) throws UnsupportedEncodingException {
            super(new OutputStreamWriter(outputStream, characterEncoding));
        }

        @Override
        public void write(char[] buf, int off, int len) {
            super.write(buf, off, len);
            super.flush();
        }

        @Override
        public void write(String s, int off, int len) {
            super.write(s, off, len);
            super.flush();
        }

        @Override
        public void write(int c) {
            super.write(c);
            super.flush();
        }
    }

}
