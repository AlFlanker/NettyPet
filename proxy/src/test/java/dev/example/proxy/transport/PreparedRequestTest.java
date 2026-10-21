package dev.example.proxy.transport;

import dev.example.proxy.api.Headers;
import dev.example.proxy.api.ProxyRequest;
import dev.example.proxy.api.ProxyRejection;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Проверяет неизменяемый план запроса и правила длины тела перед передачей владения транспорту. */
class PreparedRequestTest {
    @Test
    void snapshotFreezesUriAndRepeatedHeadersBeforeNetworkIo() {
        HttpServletRequest incoming = incoming("HTTP/1.1", 7);
        URI original = URI.create("https://upstream.example/a%2Fb?q=%0D%0A");
        Headers headers = new Headers().add("X-Custom", "first").add("X-Custom", "second")
                .set("Host", "forged.example").set("Content-Length", "999")
                .set("Connection", "X-Hop").set("X-Hop", "remove")
                .set("Transfer-Encoding", "chunked");
        ProxyRequest mutable = new ProxyRequest(original, headers);

        PreparedRequest snapshot = PreparedRequest.capture(incoming, mutable);
        mutable.uri(URI.create("http://another.example/replacement"));
        headers.add("X-Custom", "late").set("X-Added-Later", "late");

        assertEquals("POST", snapshot.method());
        assertEquals(original, snapshot.uri());
        assertEquals(List.of("first", "second"), snapshot.headers().get("x-custom"));
        assertEquals(1, snapshot.headers().size(), "Framing, authority and hop-by-hop fields are rebuilt by transport");
        assertEquals(7, snapshot.contentLength());
        assertTrue(snapshot.declaredLength());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.headers().put("x-forged", List.of("value")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.headers().get("x-custom").add("forged"));
    }

    @Test
    void http1RequestWithoutFramingHasNoBodyButDoesNotGainDeclaredLength() {
        PreparedRequest snapshot = PreparedRequest.capture(incoming("HTTP/1.1", -1), plan());

        assertEquals(0, snapshot.contentLength());
        assertFalse(snapshot.declaredLength(), "An absent Content-Length must not become an explicit CL: 0");
    }

    @Test
    void http2UnknownLengthRemainsStreamingUntilEndStream() {
        PreparedRequest snapshot = PreparedRequest.capture(incoming("HTTP/2.0", -1), plan());

        assertEquals(-1, snapshot.contentLength());
        assertFalse(snapshot.declaredLength());
    }

    @Test
    void http1ChunkedRequestRemainsStreamingDespiteUnknownLength() {
        HttpServletRequest incoming = incoming("HTTP/1.1", -1);
        when(incoming.getHeader("Transfer-Encoding")).thenReturn("chunked");

        PreparedRequest snapshot = PreparedRequest.capture(incoming, plan());

        assertEquals(-1, snapshot.contentLength());
        assertFalse(snapshot.declaredLength());
    }

    @Test
    void rejectsNtlmEvenWhenItIsNotTheFirstAuthorizationValue() {
        ProxyRequest plan = plan();
        plan.headers().add("Authorization", "Bearer ordinary-token").add("Authorization", "nTlM opaque-token");

        ProxyRejection rejection = assertThrows(ProxyRejection.class,
                () -> PreparedRequest.capture(incoming("HTTP/1.1", -1), plan));

        assertEquals(501, rejection.status());
    }

    @Test
    void rejectsNegotiateWithCaseInsensitiveSchemeAndWhitespaceSeparators() {
        ProxyRequest plan = plan();
        plan.headers().set("Authorization", " \tNeGoTiAtE\topaque-token");

        ProxyRejection rejection = assertThrows(ProxyRejection.class,
                () -> PreparedRequest.capture(incoming("HTTP/1.1", -1), plan));

        assertEquals(501, rejection.status());
    }

    @Test
    void basicAndBearerAuthenticationRemainUnchanged() {
        for (String authorization : List.of("Basic dXNlcjpwYXNz", "Bearer opaque-token")) {
            ProxyRequest plan = plan();
            plan.headers().set("Authorization", authorization);

            PreparedRequest snapshot = PreparedRequest.capture(incoming("HTTP/1.1", -1), plan);

            assertEquals(List.of(authorization), snapshot.headers().get("authorization"));
        }
    }

    private static HttpServletRequest incoming(String protocol, long contentLength) {
        HttpServletRequest incoming = mock(HttpServletRequest.class);
        when(incoming.getMethod()).thenReturn("POST");
        when(incoming.getProtocol()).thenReturn(protocol);
        when(incoming.getContentLengthLong()).thenReturn(contentLength);
        return incoming;
    }

    private static ProxyRequest plan() {
        return new ProxyRequest(URI.create("http://upstream.example/resource"), new Headers());
    }
}
