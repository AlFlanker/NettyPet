package dev.example.integrationhost;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The common application boundary never starts async or consumes a body. */
class HttpProxyFilterTest {
    private static final BackendRoute ROUTE = new BackendRoute("main", "/upload",
            URI.create("http://127.0.0.1:9000/upload"), Duration.ofSeconds(5));

    @Test
    void localRouteContinuesChainWithoutResolvingBackendOrCallingAnyHandler() throws Exception {
        HttpServletRequest request = request("/local");
        HttpServletResponse response = mock(HttpServletResponse.class);
        BackendRouteResolver routes = mock(BackendRouteResolver.class);
        ProxyRequestHandler handler = mock(ProxyRequestHandler.class);
        FilterChain chain = mock(FilterChain.class);
        new HttpProxyFilter(routes, () -> true, ignored -> true, handler).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(routes, handler, response);
        assertUntouched(request);
    }

    @Test
    void maintenanceRejectsBeforeRouteLookupOrBodyOwnership() throws Exception {
        HttpServletRequest request = request("/backend/upload");
        HttpServletResponse response = mock(HttpServletResponse.class);
        BackendRouteResolver routes = mock(BackendRouteResolver.class);
        ProxyRequestHandler handler = mock(ProxyRequestHandler.class);
        FilterChain chain = mock(FilterChain.class);
        new HttpProxyFilter(routes, () -> true, ignored -> false, handler).doFilter(request, response, chain);
        verify(response).setStatus(503);
        verifyNoInteractions(routes, handler, chain);
        assertUntouched(request);
    }

    @Test
    void missingBackendAndForbiddenRouteRejectBeforeHandler() throws Exception {
        for (boolean missing : new boolean[]{true, false}) {
            HttpServletRequest request = request("/backend/upload");
            HttpServletResponse response = mock(HttpServletResponse.class);
            ProxyRequestHandler handler = mock(ProxyRequestHandler.class);
            FilterChain chain = mock(FilterChain.class);
            new HttpProxyFilter((incoming, path) -> missing ? null : ROUTE, () -> false,
                    ignored -> true, handler).doFilter(request, response, chain);
            verify(response).setStatus(missing ? 404 : 403);
            verifyNoInteractions(handler, chain);
            assertUntouched(request);
        }
    }

    @Test
    void selectedHandlerReceivesActualObjectsExactlyOnceAndChainIsNotContinued() throws Exception {
        HttpServletRequest request = request("/backend/upload");
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicInteger calls = new AtomicInteger();
        ProxyRequestHandler handler = (incoming, outgoing, route) -> {
            assertSame(request, incoming);
            assertSame(response, outgoing);
            assertSame(ROUTE, route);
            assertUntouched(incoming);
            calls.incrementAndGet();
        };
        FilterChain chain = mock(FilterChain.class);
        new HttpProxyFilter((incoming, rawPath) -> {
            assertEquals("/upload", rawPath);
            return ROUTE;
        }, () -> false, ignored -> false, handler).doFilter(request, response, chain);
        assertEquals(1, calls.get());
        verifyNoInteractions(chain, response);
    }

    private static HttpServletRequest request(String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/app" + path);
        when(request.getContextPath()).thenReturn("/app");
        return request;
    }

    private static void assertUntouched(HttpServletRequest request)
            throws java.io.IOException, jakarta.servlet.ServletException {
        verify(request, never()).startAsync(any(), any());
        verify(request, never()).startAsync();
        verify(request, never()).getInputStream();
        verify(request, never()).getReader();
        verify(request, never()).getParameter(anyString());
        verify(request, never()).getParts();
    }
}
