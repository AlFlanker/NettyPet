package dev.example.jettyclient.proxy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jetty.http.MimeTypes;

/**
 * Завершённый ограниченный ответ. Тело принадлежит результату в обычной heap-памяти;
 * pooled input уже освобождён. HTTP ошибки backend, включая 404, остаются ответами.
 */
public final class ProxyFetchResult {
    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final int maxTextCharacters;

    private ProxyFetchResult(int status, Map<String, List<String>> headers, byte[] body, int maxTextCharacters) {
        this.status = status;
        this.headers = ProxyFetchRequest.immutableHeaders(headers);
        this.body = body;
        this.maxTextCharacters = maxTextCharacters;
    }

    /** Принимает исключительное владение новым массивом, не доступным вызывающему приложению. */
    static ProxyFetchResult owned(int status, Map<String, List<String>> headers, byte[] body, int maxTextCharacters) {
        return new ProxyFetchResult(status, headers, body, maxTextCharacters);
    }

    public int status() { return status; }
    public Map<String, List<String>> headers() { return headers; }
    public List<String> headerValues(String name) {
        return headers.getOrDefault(Objects.requireNonNull(name).toLowerCase(java.util.Locale.ROOT), List.of());
    }
    public int bodyLength() { return body.length; }

    /** Свежий read-only cursor; backing array не выдаётся приложению. */
    public ByteBuffer body() { return ByteBuffer.wrap(body).asReadOnlyBuffer(); }

    public String bodyAsString() throws IOException { return bodyAsString(StandardCharsets.UTF_8); }

    /** Content-Type charset имеет приоритет над явно заданным fallback; platform default не используется. */
    public String bodyAsString(Charset fallback) throws IOException {
        Charset charset = responseCharset(Objects.requireNonNull(fallback));
        try (var reader = new InputStreamReader(new ByteArrayInputStream(body), charset)) {
            StringBuilder result = new StringBuilder(Math.min(body.length, 4096));
            char[] part = new char[Math.min(maxTextCharacters, 4096)];
            int count;
            while ((count = reader.read(part)) >= 0) {
                if (count > maxTextCharacters - result.length()) throw new IOException("Fetch text exceeds character limit");
                result.append(part, 0, count);
            }
            return result.toString();
        }
    }

    private Charset responseCharset(Charset fallback) throws IOException {
        List<String> types = headerValues("Content-Type");
        if (types.isEmpty()) return fallback;
        try {
            String name = MimeTypes.getCharsetFromContentType(types.getFirst());
            return name == null ? fallback : Charset.forName(name);
        } catch (IllegalArgumentException failure) { throw new IOException("Unsupported fetch response charset", failure); }
    }
}
