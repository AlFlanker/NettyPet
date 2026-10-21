package dev.example.app.command;

import com.otr.portal.command.CommandConfigRegistry;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.config.CommandProperties;
import dev.example.proxy.api.ProxyResult;
import dev.example.proxy.transport.ProxyCall;
import dev.example.proxy.transport.ProxyEngine;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Проверяет передачу владения Servlet-запросом при подписке на Reactor-команду. */
class ProxyRequestHandlerTest {
    @Test
    void constructingPublisherDoesNotStartTransportAndSubscriptionHandsOffSynchronously() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var command = fixture.commands.command(fixture.engine, fixture.request, fixture.response, "/resource");
            var publisher = command.execute();
            verifyNoInteractions(fixture.engine, fixture.request, fixture.response);
            assertFalse(command.handedToProxy());

            publisher.subscribe(ignored -> { }, ignored -> { });

            assertTrue(command.handedToProxy());
            verify(fixture.engine).forwardAsync(fixture.request, fixture.response, "/resource");
            assertEquals(0, fixture.commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
            fixture.cleanedUp.complete(new ProxyResult(200, ProxyResult.Outcome.RESPONSE));
            assertEquals(1, fixture.commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
            verifyNoInteractions(fixture.request, fixture.response);
        }
    }

    @Test
    void bulkheadRejectionIsSynchronousAndDoesNotInvokeTransport() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var handler = new ProxyRequestHandler(fixture.engine, fixture.commands);
            handler.forward(fixture.request, fixture.response, "/resource");
            HttpServletRequest rejectedRequest = mock(HttpServletRequest.class);
            HttpServletResponse rejectedResponse = mock(HttpServletResponse.class);

            handler.forward(rejectedRequest, rejectedResponse, "/other");

            verify(rejectedResponse).setStatus(503);
            verify(rejectedResponse).setHeader("Connection", "close");
            verify(rejectedResponse).setContentLength(0);
            verify(fixture.engine, times(1)).forwardAsync(any(), any(), any());
            verifyNoInteractions(rejectedRequest, fixture.request, fixture.response);
            fixture.cleanedUp.complete(new ProxyResult(200, ProxyResult.Outcome.RESPONSE));
        }
    }

    @Test
    void openCircuitRejectsBeforeTransportAndClosedDecoratorRejectsNewRequests() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var handler = new ProxyRequestHandler(fixture.engine, fixture.commands);
            fixture.commands.circuitBreaker().transitionToOpenState();
            handler.forward(fixture.request, fixture.response, "/resource");
            verify(fixture.response).setStatus(503);
            verifyNoInteractions(fixture.engine, fixture.request);

            fixture.commands.close();
            HttpServletResponse response = mock(HttpServletResponse.class);
            handler.forward(fixture.request, response, "/resource");
            verify(response).setStatus(503);
            verifyNoInteractions(fixture.engine, fixture.request);
        }
    }

    @Test
    void synchronousTransportFailureIsMappedBeforeOwnershipAndLaterFailureDoesNotRewriteHttp() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.engine.forwardAsync(fixture.request, fixture.response, "/resource"))
                    .thenThrow(new IOException("transport did not take ownership"));
            var handler = new ProxyRequestHandler(fixture.engine, fixture.commands);
            handler.forward(fixture.request, fixture.response, "/resource");
            verify(fixture.response).setStatus(500);
            verify(fixture.response).setContentLength(0);
        }
        try (Fixture fixture = new Fixture()) {
            new ProxyRequestHandler(fixture.engine, fixture.commands)
                    .forward(fixture.request, fixture.response, "/resource");
            fixture.cleanedUp.complete(new ProxyResult(503, ProxyResult.Outcome.CANCELLED));
            verifyNoInteractions(fixture.response, fixture.request);
        }
    }

    @Test
    void diagnosticsPinPolicyGenerationEvenWhenIdleAndReleaseItOnShutdown() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> fixture.registry.clearCache("proxy"));
            assertThrows(IllegalStateException.class, fixture.registry::clearAllCaches);
            fixture.commands.close();
            assertDoesNotThrow(() -> fixture.registry.clearCache("proxy"));
        }
    }

    @Test
    void shutdownKeepsPolicyGenerationPinnedUntilTransportConfirmsCleanup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            new ProxyRequestHandler(fixture.engine, fixture.commands)
                    .forward(fixture.request, fixture.response, "/resource");
            fixture.commands.close();

            verify(fixture.call).cancel(503);
            assertEquals(0, fixture.commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
            assertThrows(IllegalStateException.class, () -> fixture.registry.clearCache("proxy"));
            verifyNoInteractions(fixture.request, fixture.response);

            fixture.cleanedUp.complete(new ProxyResult(503, ProxyResult.Outcome.CANCELLED));
            assertEquals(1, fixture.commands.bulkhead().getMetrics().getAvailableConcurrentCalls());
            assertDoesNotThrow(() -> fixture.registry.clearCache("proxy"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ProxyEngine engine = mock(ProxyEngine.class);
        final ProxyCall call = mock(ProxyCall.class);
        final HttpServletRequest request = mock(HttpServletRequest.class);
        final HttpServletResponse response = mock(HttpServletResponse.class);
        final CompletableFuture<ProxyResult> cleanedUp = new CompletableFuture<>();
        final ProxyCommands commands;
        final CommandConfigRegistry registry;

        Fixture() throws IOException {
            registry = new CommandConfigRegistry(BulkheadRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(),
                    CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
            commands = new ProxyCommands(registry, new CommandResilienceDecorator(registry),
                    CommandProperties.builder().maxConcurrency(1).timeout(Duration.ofSeconds(10)).build());
            when(engine.forwardAsync(request, response, "/resource")).thenReturn(call);
            when(call.completion()).thenReturn(cleanedUp.minimalCompletionStage());
        }

        @Override public void close() {
            commands.close();
            cleanedUp.complete(new ProxyResult(503, ProxyResult.Outcome.CANCELLED));
        }
    }
}
