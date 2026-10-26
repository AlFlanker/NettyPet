package dev.example.jettyclient.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Подготовленный запрос bounded fetch без Servlet и без неявного повторения тела
 * исходного запроса. Метод по умолчанию GET; остальные методы также имеют пустое тело.
 * Адрес выбирается доверенной конфигурацией приложения, не клиентским URL.
 */
public final class ProxyFetchRequest {
    private final URI target;
    private final String method;
    private final Map<String, List<String>> headers;
    private final Duration timeout;
    private final int maxWireBytes;
    private final int maxDecodedBytes;
    private final int maxTextCharacters;
    private final boolean decodeContentEncoding;

    private ProxyFetchRequest(Builder builder) {
        target = builder.target;
        method = builder.method;
        headers = immutableHeaders(builder.headers);
        timeout = builder.timeout;
        maxWireBytes = builder.maxWireBytes;
        maxDecodedBytes = builder.maxDecodedBytes;
        maxTextCharacters = builder.maxTextCharacters;
        decodeContentEncoding = builder.decodeContentEncoding;
    }

    public static Builder builder(URI target) { return new Builder(target); }
    public URI target() { return target; }
    public String method() { return method; }
    public Map<String, List<String>> headers() { return headers; }
    public Duration timeout() { return timeout; }
    public int maxWireBytes() { return maxWireBytes; }
    public int maxDecodedBytes() { return maxDecodedBytes; }
    public int maxTextCharacters() { return maxTextCharacters; }
    public boolean decodeContentEncoding() { return decodeContentEncoding; }

    static Map<String, List<String>> immutableHeaders(Map<String, List<String>> source) {
        Map<String, List<String>> copied = new LinkedHashMap<>();
        source.forEach((name, values) -> copied.merge(name.toLowerCase(Locale.ROOT), List.copyOf(values),
                (first, second) -> java.util.stream.Stream.concat(first.stream(), second.stream()).toList()));
        return java.util.Collections.unmodifiableMap(copied);
    }

    private static void token(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("HTTP token is empty");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')
                    && "!#$%&'*+-.^_`|~".indexOf(c) < 0)
                throw new IllegalArgumentException("Invalid HTTP token");
        }
    }

    private static int positiveLimit(int bytes) {
        if (bytes < 1 || bytes > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("Invalid fetch limit");
        return bytes;
    }

    public static final class Builder {
        private final URI target;
        private String method = "GET";
        private Map<String, List<String>> headers = Map.of();
        private Duration timeout;
        private int maxWireBytes = 8 * 1024 * 1024;
        private int maxDecodedBytes = 8 * 1024 * 1024;
        private int maxTextCharacters = 8 * 1024 * 1024;
        private boolean decodeContentEncoding;

        private Builder(URI target) {
            Objects.requireNonNull(target, "target");
            if (!("http".equalsIgnoreCase(target.getScheme()) || "https".equalsIgnoreCase(target.getScheme()))
                    || target.isOpaque() || target.getHost() == null || target.getRawUserInfo() != null
                    || target.getRawFragment() != null || target.getPort() == 0 || target.getPort() > 65535)
                throw new IllegalArgumentException("Fetch target must be an HTTP(S) URI without user info or fragment");
            this.target = URI.create(target.toASCIIString());
        }

        public Builder method(String method) {
            token(method);
            if (method.equals("CONNECT")) throw new IllegalArgumentException("CONNECT is unsupported");
            this.method = method;
            return this;
        }

        public Builder headers(Map<String, List<String>> headers) {
            Objects.requireNonNull(headers, "headers");
            headers.forEach((name, values) -> {
                token(name);
                for (String value : values) {
                    Objects.requireNonNull(value, "header value");
                    for (int i = 0; i < value.length(); i++) {
                        char c = value.charAt(i);
                        if (c != '\t' && (c < 0x20 || c == 0x7f || c > 0xff))
                            throw new IllegalArgumentException("Invalid HTTP field value");
                    }
                }
            });
            this.headers = immutableHeaders(headers);
            return this;
        }

        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            try {
                if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() < 1)
                    throw new IllegalArgumentException("Fetch timeout must be at least 1ms");
                timeout.toNanos();
            } catch (ArithmeticException failure) { throw new IllegalArgumentException("Fetch timeout is too large", failure); }
            this.timeout = timeout;
            return this;
        }

        public Builder maxWireBytes(int bytes) { maxWireBytes = positiveLimit(bytes); return this; }
        public Builder maxDecodedBytes(int bytes) { maxDecodedBytes = positiveLimit(bytes); return this; }
        public Builder maxTextCharacters(int characters) { maxTextCharacters = positiveLimit(characters); return this; }
        public Builder decodeContentEncoding(boolean decode) { decodeContentEncoding = decode; return this; }
        public ProxyFetchRequest build() { return new ProxyFetchRequest(this); }
    }
}
