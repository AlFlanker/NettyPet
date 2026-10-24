package dev.example.jettyclient.proxy;

import java.net.URI;
import java.util.Objects;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpConversation;
import org.eclipse.jetty.client.transport.HttpRequest;

/** Jetty's convenience method setter uppercases tokens; a proxy must preserve their case. */
final class ProxyHttpClient extends HttpClient {
    ProxyHttpClient(HttpClientTransport transport) { super(transport); }

    @Override public Request newRequest(URI uri) {
        return new ProxyRequest(this, new HttpConversation(), uri);
    }

    private static final class ProxyRequest extends HttpRequest {
        private String originalMethod = "GET";

        ProxyRequest(HttpClient client, HttpConversation conversation, URI uri) {
            super(client, conversation, uri);
        }

        @Override public Request method(String method) {
            super.method(method);
            originalMethod = Objects.requireNonNull(method);
            return this;
        }

        @Override public String getMethod() { return originalMethod; }

        @Override protected HttpRequest copyInstance(URI uri) {
            return new ProxyRequest(getHttpClient(), getConversation(), uri);
        }
    }
}
