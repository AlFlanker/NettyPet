package dev.example.app.web;

import dev.example.app.command.ProxyRequestHandler;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.Mockito.*;

class ProxyFilterTest {
    @Test
    void unrelatedAndSimilarPathsContinueTheApplicationChain() throws Exception {
        for (String path : new String[]{"/local", "/proxying", "/proxy-other", "/Proxy/items", "/x/proxy/items"}) {
            ProxyRequestHandler handler = mock(ProxyRequestHandler.class);
            ProxyFilter filter = new ProxyFilter(handler);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            verifyNoInteractions(handler);
            verify(chain).doFilter(request, response);
        }
    }

    @Test
    void matchingPrefixDelegatesOriginalRequestAndRawPath() throws Exception {
        ProxyRequestHandler handler = mock(ProxyRequestHandler.class);
        ProxyFilter filter = new ProxyFilter(handler);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/gateway/proxy/a%20b");
        request.setContextPath("/gateway");
        request.setQueryString("x=1&x=2&encoded=%2F");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        verify(handler).forward(request, response, "/a%20b");
        verifyNoInteractions(chain);
    }

    @Test
    void exactPrefixAndPrefixSlashBothRouteToRoot() throws Exception {
        for (String path : new String[]{"/proxy", "/proxy/"}) {
            ProxyRequestHandler handler = mock(ProxyRequestHandler.class);
            ProxyFilter filter = new ProxyFilter(handler);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            verify(handler).forward(request, response, "/");
            verifyNoInteractions(chain);
        }
    }
}
