package com.otr.portal.proxy;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

/**
 * @author pobedenniy.alexey
 * @since 18.07.16
 */
class PrintToStringHttpServletResponse implements HttpServletResponse {

    private StringWriter stringWriter;
    private ByteArrayServletOutputStream outputStream;
    private int status;
    private List<Cookie> cookies;
    private Map<String, String> headers;

    @Override
    public PrintWriter getWriter() throws IOException {
        stringWriter = new StringWriter();
        return new PrintWriter(stringWriter);
    }

    String asString() {
        return stringWriter != null ? stringWriter.toString() : outputStream.asString();
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        outputStream = new ByteArrayServletOutputStream();
        return outputStream;
    }

    @Override
    public void addCookie(Cookie cookie) {
        if (this.cookies == null) {
            this.cookies = new ArrayList<>(3);
        }
        this.cookies.add(cookie);
        headers().put(HttpHeaders.SET_COOKIE, CookieUtils.generateHeader(cookie));
    }

    private Map<String, String> headers() {
        return headers == null ? headers = new HashMap<>() : headers;
    }

    public String getCookieValueByName(String name) {
        return cookies == null ?
                null :
                cookies
                        .stream()
                        .filter(cookie -> name.equals(cookie.getName()))
                        .map(Cookie::getValue)
                        .filter(value -> value != null)
                        .findFirst()
                        .orElse(null);
    }

    @Override
    public boolean containsHeader(String name) {
        return headers != null && headers.containsKey(name);
    }

    @Override
    public String encodeURL(String url) {
        return null;
    }

    @Override
    public String encodeRedirectURL(String url) {
        return null;
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {

    }

    @Override
    public void sendError(int sc) throws IOException {

    }

    @Override
    public void sendRedirect(String location) throws IOException {

    }

    @Override
    public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
        sendRedirect(location);
    }

    @Override
    public void setDateHeader(String name, long date) {

    }

    @Override
    public void addDateHeader(String name, long date) {

    }

    @Override
    public void setHeader(String name, String value) {
        headers().put(name, value);

    }

    @Override
    public void addHeader(String name, String value) {
        headers().put(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {

    }

    @Override
    public void addIntHeader(String name, int value) {

    }

    @Override
    public void setStatus(int sc) {
        status = sc;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public String getHeader(String name) {
        return headers().get(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return null;
    }

    @Override
    public Collection<String> getHeaderNames() {
        return Collections.unmodifiableCollection(headers().keySet());
    }

    @Override
    public String getCharacterEncoding() {
        return null;
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public void setCharacterEncoding(String charset) {

    }

    @Override
    public void setContentLength(int len) {

    }

    @Override
    public void setContentLengthLong(long length) {

    }

    @Override
    public void setContentType(String type) {

    }

    @Override
    public void setBufferSize(int size) {

    }

    @Override
    public int getBufferSize() {
        return 0;
    }

    @Override
    public void flushBuffer() throws IOException {

    }

    @Override
    public void resetBuffer() {

    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    @Override
    public void reset() {

    }

    @Override
    public void setLocale(Locale loc) {

    }

    @Override
    public Locale getLocale() {
        return null;
    }

    private static class ByteArrayServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream byteArray;

        ByteArrayServletOutputStream() {
            this.byteArray = new ByteArrayOutputStream();
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {

        }

        @Override
        public void write(int b) throws IOException {
            byteArray.write(b);
        }

        String asString() {
            return byteArray.toString();
        }
    }
}
