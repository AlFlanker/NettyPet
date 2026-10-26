package dev.example.jettyclient.proxy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A local 400..599 response; immutable nonpooled body, at most 64 KiB. */
public final class ProxyErrorResponse {
    public static final int MAX_BODY_BYTES = 65_536;
    private static final Set<String> UNSAFE = Set.of("host", "content-length", "expect", "connection",
            "keep-alive", "proxy-connection", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "content-encoding", "content-range", "etag", "content-md5",
            "digest", "content-digest", "repr-digest", "last-modified", "accept-ranges");
    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    public ProxyErrorResponse(int status, Map<String, List<String>> headers, byte[] body) {
        if (status < 400 || status > 599) throw new IllegalArgumentException("Error status must be 400..599");
        Objects.requireNonNull(body, "body");
        if (body.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Error body exceeds 64 KiB");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        Objects.requireNonNull(headers, "headers").forEach((name, values) -> {
            ProxyHeaderEditor.validateName(name);
            String lower = name.toLowerCase(Locale.ROOT);
            if (UNSAFE.contains(lower)) throw new IllegalArgumentException("Proxy controls error header: " + name);
            List<String> entries = List.copyOf(values);
            entries.forEach(ProxyHeaderEditor::validateValue);
            copy.computeIfAbsent(lower, ignored -> new ArrayList<>()).addAll(entries);
        });
        copy.replaceAll((name, values) -> List.copyOf(values));
        this.headers = Collections.unmodifiableMap(copy);
        this.body = body.clone();
        this.status = status;
    }

    public static ProxyErrorResponse text(int status, String text) {
        return new ProxyErrorResponse(status, Map.of("Content-Type", List.of("text/plain; charset=UTF-8")),
                Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8));
    }

    public int status() { return status; }
    public Map<String, List<String>> headers() { return headers; }
    public byte[] body() { return body.clone(); }

    ByteBuffer bodyBuffer() { return ByteBuffer.wrap(body).asReadOnlyBuffer(); }
}
