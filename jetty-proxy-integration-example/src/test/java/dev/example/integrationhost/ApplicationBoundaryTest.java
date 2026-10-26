package dev.example.integrationhost;

import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.ProxyRequestPlan;
import dev.example.proxy.transport.ProxyEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApplicationBoundaryTest {
    private static final HostProxyProperties FIXED = new HostProxyProperties(
            URI.create("http://127.0.0.1:9000/base"), Duration.ofSeconds(5), false);

    @Test
    void resourceMaintenanceMissingBackendAndForbiddenUseTheCommonPolicyBeforeLoading() throws Exception {
        for (int status : new int[]{503, 404, 403}) {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRequestURI()).thenReturn("/resource");
            when(request.getContextPath()).thenReturn("");
            when(request.getQueryString()).thenReturn("path=%2Fforbidden");
            BackendRouteResolver resolver = mock(BackendRouteResolver.class);
            when(resolver.resolve(any(), anyString())).thenReturn(status == 404 ? null :
                    new BackendRoute("main", "/forbidden", URI.create("http://127.0.0.1:9000/base/forbidden"),
                            Duration.ofSeconds(5)));
            var policy = new ApplicationRoutePolicy(resolver, () -> status == 503, ApplicationRoutePolicy::forbidden);
            HttpServletResponse response = mock(HttpServletResponse.class);
            jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);
            ProxyRequestHandler handler = mock(ProxyRequestHandler.class);
            new HttpProxyFilter(policy, handler).doFilter(request, response, chain);
            verify(response).setStatus(status);
            verifyNoInteractions(chain, handler);
            if (status == 503) verify(resolver, never()).resolve(any(), anyString());
            untouched(request);
        }
    }

    @Test
    void resourceRouteIsAuthorizedFromQueryWithoutReadingPostParametersAndPassedToMvcOnce() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/host/resource");
        when(request.getContextPath()).thenReturn("/host");
        when(request.getQueryString()).thenReturn("path=%2Finclude%2Fa%2520b%3Fx%3D1%26x%3D2");
        when(request.getMethod()).thenReturn("POST");
        BackendRouteResolver resolver = mock(BackendRouteResolver.class);
        var route = new BackendRoute("main", "/include/a%20b", URI.create("http://127.0.0.1:9000/base/include/a%20b?x=1&x=2"),
                Duration.ofSeconds(5));
        when(resolver.resolve(request, "/include/a%20b?x=1&x=2")).thenReturn(route);
        var policy = new ApplicationRoutePolicy(resolver, () -> false, ApplicationRoutePolicy::forbidden);
        HttpServletResponse response = mock(HttpServletResponse.class);
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);
        ProxyRequestHandler handler = mock(ProxyRequestHandler.class);
        new HttpProxyFilter(policy, handler).doFilter(request, response, chain);
        verify(resolver, times(1)).resolve(request, "/include/a%20b?x=1&x=2");
        verify(request).setAttribute(HttpProxyFilter.RESOURCE_ROUTE_ATTRIBUTE, route);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(handler, response);
        untouched(request);
    }

    @Test
    void legacyRejectsTargetAndTimeoutMismatchBeforeStartingItsEngineOrReadingTheBody() throws Exception {
        for (boolean changeTarget : new boolean[]{true, false}) {
            ProxyEngine engine = mock(ProxyEngine.class);
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getQueryString()).thenReturn("x=1");
            HttpServletResponse response = mock(HttpServletResponse.class);
            var route = new BackendRoute("chosen", "/upload", URI.create("http://127.0.0.1:9000/"
                    + (changeTarget ? "other" : "base/upload") + "?x=1"),
                    changeTarget ? FIXED.timeout() : Duration.ofSeconds(2));
            new LegacyProxyHandler(engine, FIXED).handle(request, response, route);
            verify(response).setStatus(501);
            verify(response).setHeader("X-Proxy-Capability", "fixed-backend-and-timeout-only");
            verifyNoInteractions(engine);
            untouched(request);
        }
    }

    @Test
    void jettyReceivesTheSelectedDynamicTargetAndTimeout() {
        JettyProxyEngine engine = mock(JettyProxyEngine.class);
        var route = new BackendRoute("dynamic", "/input", URI.create("http://127.0.0.1:9001/selected?x=2"),
                Duration.ofSeconds(2));
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        new JettyProxyHandler(engine).handle(request, response, route);
        var plan = ArgumentCaptor.forClass(ProxyRequestPlan.class);
        verify(engine).forward(same(request), same(response), plan.capture());
        assertEquals(route.target(), plan.getValue().target());
        assertEquals(route.timeout(), plan.getValue().timeout());
    }

    private static void untouched(HttpServletRequest request) throws Exception {
        verify(request, never()).startAsync();
        verify(request, never()).startAsync(any(), any());
        verify(request, never()).getInputStream();
        verify(request, never()).getReader();
        verify(request, never()).getParameter(anyString());
        verify(request, never()).getParts();
    }
}
