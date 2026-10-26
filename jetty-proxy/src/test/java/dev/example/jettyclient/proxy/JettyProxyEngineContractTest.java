package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public handoff contracts needed by an application Filter, independent of Servlet mapping. */
class JettyProxyEngineContractTest {
    private static final URI VALID_TARGET = URI.create("http://upstream.example/resource");

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTargets")
    void rejectsInvalidResolvedTargetBeforeAsyncAdmissionOrClientUse(String description, URI target)
            throws Exception {
        HttpClient client = mock(ProxyHttpClient.class);
        HttpServletRequest incoming = incoming();
        HttpServletResponse outgoing = mock(HttpServletResponse.class);
        try (JettyProxyEngine engine = new JettyProxyEngine(client, properties())) {
            engine.forward(incoming, outgoing, target);

            verify(outgoing).setStatus(400);
            verify(outgoing).setContentLength(0);
            verify(incoming, never()).startAsync(any(), any());
            verify(incoming, never()).getInputStream();
            assertEquals(0, engine.activeRequests());
            verifyNoInteractions(client);
        }
    }

    static Stream<Arguments> invalidTargets() {
        return Stream.of(
                Arguments.of("null", (URI)null),
                Arguments.of("relative", URI.create("/resource")),
                Arguments.of("unsupported scheme", URI.create("ftp://upstream.example/resource")),
                Arguments.of("opaque URI", URI.create("http:resource")),
                Arguments.of("missing host", URI.create("http:/resource")),
                Arguments.of("userinfo", URI.create("http://user:password@upstream.example/resource")),
                Arguments.of("fragment", URI.create("http://upstream.example/resource#fragment")),
                Arguments.of("zero port", URI.create("http://upstream.example:0/resource")),
                Arguments.of("port above range", URI.create("http://upstream.example:65536/resource")));
    }

    @Test
    void usesResolvedRawTargetWithoutDependingOnProxyServletMapping() throws Exception {
        String rawTarget = "http://upstream.example:8080/base//a%2Fb/%E2%82%AC"
                + "?x=1&x=2&plus=a+b&encoded=%252F&empty=";
        HttpClient client = mock(ProxyHttpClient.class);
        when(client.getExecutor()).thenReturn((Executor)Runnable::run);
        // Stop at the client boundary: this test verifies route handoff, not network streaming.
        when(client.newRequest(any(URI.class))).thenThrow(new IllegalStateException("Client boundary reached"));
        HttpServletRequest incoming = incoming();
        when(incoming.getRequestURI()).thenReturn("/application/legacy-route/unrelated");
        when(incoming.getContextPath()).thenReturn("/application");
        when(incoming.getServletPath()).thenReturn("/legacy-route");
        when(incoming.getPathInfo()).thenReturn("/unrelated");
        HttpServletResponse outgoing = mock(HttpServletResponse.class);
        when(outgoing.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
        AsyncContext async = mock(AsyncContext.class);
        when(incoming.startAsync(incoming, outgoing)).thenReturn(async);

        try (JettyProxyEngine engine = new JettyProxyEngine(client, properties())) {
            engine.forward(incoming, outgoing, URI.create(rawTarget));

            ArgumentCaptor<URI> forwarded = ArgumentCaptor.forClass(URI.class);
            verify(client).newRequest(forwarded.capture());
            assertEquals(rawTarget, forwarded.getValue().toASCIIString(),
                    "The transport must preserve the application's encoded path and raw query");
            verify(incoming).startAsync(incoming, outgoing);
            verify(async).complete();
            assertEquals(0, engine.activeRequests(), "A failed handoff must release admission");
        }
    }

    @Test
    void rejectsUnsupportedAsyncBeforeTakingOwnership() throws Exception {
        HttpServletRequest incoming = incoming();
        when(incoming.isAsyncSupported()).thenReturn(false);
        assertInvalidAsyncState(incoming);
    }

    @Test
    void rejectsAlreadyStartedAsyncBeforeTakingOwnership() throws Exception {
        HttpServletRequest incoming = incoming();
        when(incoming.isAsyncStarted()).thenReturn(true);
        assertInvalidAsyncState(incoming);
    }

    @Test
    void closedEngineRejectsWithoutStartingAsyncOrUsingClient() throws Exception {
        HttpClient client = mock(ProxyHttpClient.class);
        HttpServletRequest incoming = incoming();
        HttpServletResponse outgoing = mock(HttpServletResponse.class);
        try (JettyProxyEngine engine = new JettyProxyEngine(client, properties())) {
            engine.close();
            engine.forward(incoming, outgoing, VALID_TARGET);

            verify(outgoing).setStatus(503);
            verify(incoming, never()).startAsync(any(), any());
            verify(incoming, never()).getInputStream();
            assertEquals(0, engine.activeRequests());
            verifyNoInteractions(client);
        }
    }

    private static void assertInvalidAsyncState(HttpServletRequest incoming) throws Exception {
        HttpClient client = mock(ProxyHttpClient.class);
        HttpServletResponse outgoing = mock(HttpServletResponse.class);
        try (JettyProxyEngine engine = new JettyProxyEngine(client, properties())) {
            engine.forward(incoming, outgoing, VALID_TARGET);

            verify(outgoing).setStatus(500);
            verify(incoming, never()).startAsync(any(), any());
            verify(incoming, never()).getInputStream();
            assertEquals(0, engine.activeRequests());
            verifyNoInteractions(client);
        }
    }

    private static HttpServletRequest incoming() {
        HttpServletRequest incoming = mock(HttpServletRequest.class);
        when(incoming.getMethod()).thenReturn("GET");
        when(incoming.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(incoming.getHeaders(anyString())).thenAnswer(ignored -> Collections.emptyEnumeration());
        when(incoming.getContentLengthLong()).thenReturn(-1L);
        when(incoming.isAsyncSupported()).thenReturn(true);
        return incoming;
    }

    private static JettyProxyProperties properties() {
        return new JettyProxyProperties(URI.create("http://127.0.0.1:9000"),
                1024, 16_384, 1, 1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));
    }
}
