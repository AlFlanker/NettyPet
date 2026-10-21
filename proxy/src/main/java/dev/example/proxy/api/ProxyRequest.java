package dev.example.proxy.api;

import java.net.URI;
import java.util.Objects;

/**
 * Изменяемый план исходящего запроса до начала сетевого I/O. URI и заголовки
 * повторно проверяются транспортом после цепочки: Processor не задаёт framing/Host.
 */
public final class ProxyRequest {
    private URI uri;
    private final Headers headers;

    public ProxyRequest(URI uri, Headers headers) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.headers = Objects.requireNonNull(headers, "headers");
    }

    public URI uri() { return uri; }

    public void uri(URI uri) { this.uri = Objects.requireNonNull(uri, "uri"); }

    public Headers headers() { return headers; }
}
