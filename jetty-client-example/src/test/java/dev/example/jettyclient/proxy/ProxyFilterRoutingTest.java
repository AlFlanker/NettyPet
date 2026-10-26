package dev.example.jettyclient.proxy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Selection must finish before any transport takes ownership of the incoming body. */
class ProxyFilterRoutingTest {
    @Test
    void localRouteContinuesTheChainOnceWithoutStartingAsyncOrReadingBody() throws Exception {
        ProxyRequestPlanner planner = mock(ProxyRequestPlanner.class);
        ProxyExchangeExecutor executor = mock(ProxyExchangeExecutor.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        new JettyProxyFilter(executor, planner).doFilter(request, response, chain);

        InOrder order = inOrder(planner, chain);
        order.verify(planner).prepare(request);
        order.verify(chain).doFilter(request, response);
        verifyNoMoreInteractions(planner, chain);
        verifyNoInteractions(executor);
        assertBodyAndAsyncUntouched(request);
    }

    @Test
    void selectedRoutePassesActualWrappersAndPlanToOneExecutorWithoutContinuingChain() throws Exception {
        ProxyRequestPlanner planner = mock(ProxyRequestPlanner.class);
        ProxyExchangeExecutor executor = mock(ProxyExchangeExecutor.class);
        HttpServletRequest delegate = mock(HttpServletRequest.class);
        HttpServletRequest request = new HttpServletRequestWrapper(delegate);
        HttpServletResponse response = new HttpServletResponseWrapper(mock(HttpServletResponse.class));
        FilterChain chain = mock(FilterChain.class);
        ProxyRequestPlan plan = ProxyRequestPlan.builder(URI.create("http://backend.example/raw%20path?x=%2F"))
                .build();
        when(planner.prepare(request)).thenReturn(plan);

        new JettyProxyFilter(executor, planner).doFilter(request, response, chain);

        InOrder order = inOrder(planner, executor);
        order.verify(planner).prepare(same(request));
        order.verify(executor).forward(same(request), same(response), isNull(), same(plan));
        verifyNoMoreInteractions(planner, executor);
        verifyNoInteractions(chain);
        assertBodyAndAsyncUntouched(delegate);
    }

    @Test
    void preparationFailurePropagatesBeforeEitherTransportOrChainTakesOwnership() throws Exception {
        ProxyRequestPlanner planner = mock(ProxyRequestPlanner.class);
        ProxyExchangeExecutor executor = mock(ProxyExchangeExecutor.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        IOException failure = new IOException("Backend preparation failed");
        when(planner.prepare(request)).thenThrow(failure);

        IOException actual = assertThrows(IOException.class,
                () -> new JettyProxyFilter(executor, planner).doFilter(request, response, chain));

        assertSame(failure, actual);
        verifyNoInteractions(executor, chain);
        assertBodyAndAsyncUntouched(request);
    }

    private static void assertBodyAndAsyncUntouched(HttpServletRequest request) throws IOException, ServletException {
        verify(request, never()).getInputStream();
        verify(request, never()).getReader();
        verify(request, never()).getParameter(anyString());
        verify(request, never()).getParameterMap();
        verify(request, never()).getParts();
        verify(request, never()).getPart(anyString());
        verify(request, never()).startAsync();
        verify(request, never()).startAsync(any(), any());
    }
}
