package dev.example.proxy.transport;

import dev.example.proxy.api.ProxyOptions;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProxyEngineOwnershipTest {
    @Test
    void fatalProcessorErrorReturnsAdmissionAndPropagatesWithoutHttpErrorMapping() throws Exception {
        AssertionError fatal = new AssertionError("Processor invariant failed");
        HttpServletRequest request = request();
        HttpServletResponse response = mock(HttpServletResponse.class);
        try (MockedConstruction<NettyTransport> construction = mockConstruction(NettyTransport.class,
                (transport, ignored) -> when(transport.tryAcquire()).thenReturn(true));
             ProxyEngine engine = new ProxyEngine(options(), List.of((incoming, plan, next) -> { throw fatal; }),
                     (incoming, failure) -> fail("Async has not started"))) {
            assertSame(fatal, assertThrows(AssertionError.class, () -> engine.forward(request, response, "/resource")));

            verify(construction.constructed().getFirst(), times(1)).releaseAdmission();
            verify(request, never()).startAsync(any(), any());
            verifyNoInteractions(response);
        }
    }

    @Test
    void fatalExchangeConstructionErrorAbortsAsyncAndReturnsAdmissionEvenIfAbortFails() throws Exception {
        AssertionError fatal = new AssertionError("Event loop unavailable");
        IllegalStateException abortFailure = new IllegalStateException("Container abort failed");
        HttpServletRequest request = request();
        HttpServletResponse response = mock(HttpServletResponse.class);
        AsyncContext async = mock(AsyncContext.class);
        when(request.startAsync(request, response)).thenReturn(async);
        try (MockedConstruction<NettyTransport> construction = mockConstruction(NettyTransport.class,
                (transport, ignored) -> {
                    when(transport.tryAcquire()).thenReturn(true);
                    when(transport.permits(any())).thenReturn(true);
                    when(transport.nextLoop()).thenThrow(fatal);
                    doThrow(abortFailure).when(transport).abortDownstream(request, fatal);
                });
             ProxyEngine engine = new ProxyEngine(options(), List.of(), (incoming, failure) -> { })) {
            assertSame(fatal, assertThrows(AssertionError.class, () -> engine.forward(request, response, "/resource")));

            NettyTransport transport = construction.constructed().getFirst();
            verify(transport, times(1)).releaseAdmission();
            verify(transport).abortDownstream(request, fatal);
            assertArrayEquals(new Throwable[] { abortFailure }, fatal.getSuppressed());
            verify(async, never()).complete();
            verifyNoInteractions(response);
        }
    }

    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getProtocol()).thenReturn("HTTP/1.1");
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.isAsyncSupported()).thenReturn(true);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getHeaders("Transfer-Encoding")).thenReturn(Collections.emptyEnumeration());
        return request;
    }

    private static ProxyOptions options() {
        return new ProxyOptions(URI.create("http://localhost:18080"), 1, 2, 1, 1,
                1024, 4096, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofSeconds(10), List.of());
    }
}
