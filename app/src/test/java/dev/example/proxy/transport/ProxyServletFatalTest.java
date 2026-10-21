package dev.example.proxy.transport;

import com.otr.portal.command.CommandConfigRegistry;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.config.CommandProperties;
import dev.example.app.command.ProxyCommands;
import dev.example.app.command.ProxyRequestHandler;
import dev.example.proxy.api.ProxyOptions;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Проверяет startAsync() и аварийное прерывание настоящего ProxyEngine при ошибке запуска. */
class ProxyServletFatalTest {
    @Test
    void failureAfterStartAsyncAbortsAndPropagatesOriginalErrorWithoutWritingResponse() throws Exception {
        AssertionError fatal = new AssertionError("Event loop unavailable after startAsync");
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        AsyncContext async = mock(AsyncContext.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getProtocol()).thenReturn("HTTP/1.1");
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.isAsyncSupported()).thenReturn(true);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getHeaders("Transfer-Encoding")).thenReturn(Collections.emptyEnumeration());
        when(request.startAsync(request, response)).thenReturn(async);
        var registry = new CommandConfigRegistry(BulkheadRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(),
                CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
        AtomicBoolean transportAdmitted = new AtomicBoolean();
        try (var commands = new ProxyCommands(registry, new CommandResilienceDecorator(registry),
                     CommandProperties.builder().maxConcurrency(1).build());
             var construction = mockConstruction(NettyTransport.class, (transport, ignored) -> {
                 when(transport.tryAcquire()).thenAnswer(invocation -> transportAdmitted.compareAndSet(false, true));
                 doAnswer(invocation -> { transportAdmitted.set(false); return null; })
                         .when(transport).releaseAdmission();
                 when(transport.permits(any())).thenReturn(true);
                 when(transport.nextLoop()).thenThrow(fatal);
             });
             var engine = new ProxyEngine(new ProxyOptions(URI.create("http://127.0.0.1:18080"),
                     1, 1, 1, 1, 1024, 4096, Duration.ofSeconds(1), Duration.ofSeconds(1),
                     Duration.ofSeconds(5), Duration.ofSeconds(10), List.of()), List.of(),
                     (incoming, failure) -> { })) {
            assertSame(fatal, assertThrows(AssertionError.class, () ->
                    new ProxyRequestHandler(engine, commands).forward(request, response, "/resource")));

            var transport = construction.constructed().getFirst();
            var order = inOrder(request, transport);
            order.verify(request).startAsync(request, response);
            order.verify(transport).abortDownstream(request, fatal);
            order.verify(transport).releaseAdmission();
            assertFalse(transportAdmitted.get(), "The transport must return its acquired admission on fatal start");
            assertEquals(1, commands.bulkhead().getMetrics().getAvailableConcurrentCalls(),
                    "The command's permit must be returned after failed task startup");
            verifyNoInteractions(response, async);
        }
    }
}
