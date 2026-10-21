package dev.example.app.command;

import com.otr.portal.command.CommandConfigRegistry;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.config.CommandProperties;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import dev.example.proxy.api.ProxyResult;
import dev.example.proxy.transport.ProxyCall;
import dev.example.proxy.transport.ProxyEngine;
import com.otr.portal.command.execution.CancellationReason;
import com.otr.portal.command.execution.CommandCancelledException;
import com.otr.portal.command.execution.CommandNotExecutedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Проверяем границу владения между HTTP-транспортом и независимой командой. */
class ProxyRequestCommandTest {
    @Test
    void upstreamResultsRemainObservableOnlyAfterTransportCleanup() throws Exception {
        for (ProxyResult expected : List.of(
                new ProxyResult(200, ProxyResult.Outcome.RESPONSE),
                new ProxyResult(503, ProxyResult.Outcome.RESPONSE),
                new ProxyResult(502, ProxyResult.Outcome.UPSTREAM_FAILURE))) {
            Fixture fixture = fixture();
            assertFalse(fixture.command.handedToProxy());
            verifyNoInteractions(fixture.engine, fixture.request, fixture.response);

            var task = fixture.command.doStart();
            var result = task.completion().toCompletableFuture();
            assertTrue(fixture.command.handedToProxy());
            assertSame(task.completion(), task.completion());
            assertFalse(result.isDone(), "Returning from start is not completion of the HTTP exchange");

            fixture.cleanedUp.complete(expected);

            assertSame(expected, result.join(), "Upstream status/outcome must reach the circuit breaker unchanged");
            verify(fixture.engine, times(1)).forwardAsync(fixture.request, fixture.response, "/resource");
            verifyNoInteractions(fixture.request, fixture.response);
        }
    }

    @Test
    void localOutcomesBecomeDistinctExceptionsForCircuitBreakerIgnorePolicy() throws Exception {
        assertMapped(new ProxyResult(0, ProxyResult.Outcome.CANCELLED), CommandCancelledException.class);
        assertMapped(new ProxyResult(503, ProxyResult.Outcome.LOCAL_REJECTION), CommandNotExecutedException.class);
        assertMapped(new ProxyResult(504, ProxyResult.Outcome.DEADLINE_EXCEEDED), TimeoutException.class);
    }

    @Test
    void cancellationOnlySignalsTransportAndWaitsForItsCleanup() throws Exception {
        for (CancellationReason reason : CancellationReason.values()) {
            Fixture fixture = fixture();
            var task = fixture.command.doStart();
            var result = task.completion().toCompletableFuture();

            task.cancel(reason);

            verify(fixture.call).cancel(reason == CancellationReason.TIMEOUT ? 504 : 503);
            assertFalse(result.isDone(), "Cancellation signal must not release bulkhead before transport cleanup");
            verifyNoInteractions(fixture.request, fixture.response);

            fixture.cleanedUp.complete(new ProxyResult(503, ProxyResult.Outcome.CANCELLED));
            CompletionException failure = assertThrows(CompletionException.class, result::join);
            assertInstanceOf(CommandCancelledException.class, failure.getCause());
        }
    }

    @Test
    void commandCannotStartTransportTwiceAndDoesNotClaimOwnershipWhenStartThrows() throws Exception {
        Fixture started = fixture();
        started.command.doStart();
        assertThrows(IllegalStateException.class, started.command::doStart);
        verify(started.engine, times(1)).forwardAsync(started.request, started.response, "/resource");

        Fixture rejected = fixture();
        IOException failure = new IOException("Transport did not take ownership");
        when(rejected.engine.forwardAsync(rejected.request, rejected.response, "/resource")).thenThrow(failure);
        assertSame(failure, assertThrows(IOException.class, rejected.command::doStart));
        assertFalse(rejected.command.handedToProxy());
        verifyNoInteractions(rejected.request, rejected.response);
    }

    private static void assertMapped(ProxyResult transportResult, Class<? extends Throwable> expected) throws Exception {
        Fixture fixture = fixture();
        var result = fixture.command.doStart().completion().toCompletableFuture();
        fixture.cleanedUp.complete(transportResult);
        CompletionException failure = assertThrows(CompletionException.class, result::join);
        assertInstanceOf(expected, failure.getCause());
        verifyNoInteractions(fixture.request, fixture.response);
    }

    private static Fixture fixture() throws IOException {
        ProxyEngine engine = mock(ProxyEngine.class);
        ProxyCall call = mock(ProxyCall.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        CompletableFuture<ProxyResult> cleanedUp = new CompletableFuture<>();
        when(engine.forwardAsync(request, response, "/resource")).thenReturn(call);
        when(call.completion()).thenReturn(cleanedUp.minimalCompletionStage());
        return new Fixture(engine, call, request, response, cleanedUp,
                new ProxyRequestCommand(decorator(), CommandProperties.defaultProperties(), engine, request, response, "/resource"));
    }

    private static CommandResilienceDecorator decorator() {
        return new CommandResilienceDecorator(new CommandConfigRegistry(BulkheadRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults()));
    }

    private record Fixture(ProxyEngine engine, ProxyCall call, HttpServletRequest request,
                           HttpServletResponse response, CompletableFuture<ProxyResult> cleanedUp,
                           ProxyRequestCommand command) { }
}
