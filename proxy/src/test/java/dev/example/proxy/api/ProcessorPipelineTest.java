package dev.example.proxy.api;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProcessorPipelineTest {
    private final IncomingRequest incoming = new IncomingRequest("POST", "/x", null, Map.of(), "127.0.0.1");
    private final ProxyRequest outgoing = new ProxyRequest(URI.create("http://localhost/x"), new Headers());

    @Test
    void orderedProcessorsCanWrapTheRestOfTheChain() throws Exception {
        var events = new ArrayList<String>();
        var pipeline = new ProcessorPipeline(List.of(
                (in, out, next) -> {
                    events.add("before-first");
                    out.headers().set("X-Identity", "first");
                    next.proceed();
                    events.add("after-first");
                },
                (in, out, next) -> {
                    events.add("second");
                    assertEquals("first", out.headers().first("x-identity"));
                    out.uri(URI.create("http://localhost/rewritten"));
                    next.proceed();
                }));
        pipeline.execute(incoming, outgoing);
        assertEquals(List.of("before-first", "second", "after-first"), events);
        assertEquals("/rewritten", outgoing.uri().getRawPath());
    }

    @Test
    void proceedingTwiceCannotRunLaterProcessorsTwice() {
        int[] calls = {0};
        var pipeline = new ProcessorPipeline(List.of(
                (in, out, next) -> { next.proceed(); next.proceed(); },
                (in, out, next) -> { calls[0]++; next.proceed(); }));
        assertThrows(IllegalStateException.class, () -> pipeline.execute(incoming, outgoing));
        assertEquals(1, calls[0]);
    }

    @Test
    void silentShortCircuitIsAnError() {
        var pipeline = new ProcessorPipeline(List.of((in, out, next) -> {}));
        assertThrows(IllegalStateException.class, () -> pipeline.execute(incoming, outgoing));
    }

    @Test
    void explicitRejectionKeepsItsStatusAndStopsLaterProcessors() {
        var rejection = new ProxyRejection(403, "Forbidden");
        var pipeline = new ProcessorPipeline(List.of(
                (in, out, next) -> { throw rejection; },
                (in, out, next) -> fail("must not run")));
        assertSame(rejection, assertThrows(ProxyRejection.class, () -> pipeline.execute(incoming, outgoing)));
        assertEquals(403, rejection.status());
    }

    @Test
    void swallowingAnIncompleteDownstreamChainCannotAuthorizeForwarding() {
        var rejection = new ProxyRejection(403, "Forbidden");
        var pipeline = new ProcessorPipeline(List.of(
                (in, out, next) -> {
                    try { next.proceed(); }
                    catch (ProxyRejection ignored) { /* Ошибка внешнего процессора. */ }
                },
                (in, out, next) -> { throw rejection; }));
        assertSame(rejection, assertThrows(ProxyRejection.class, () -> pipeline.execute(incoming, outgoing)));
    }

    @Test
    void swallowedRejectionAfterProceedStillPreventsForwarding() {
        var rejection = new ProxyRejection(403, "Denied after downstream validation");
        var pipeline = new ProcessorPipeline(List.of(
                (in, out, next) -> {
                    try { next.proceed(); }
                    catch (ProxyRejection ignored) { /* Перехват исключения не отменяет отказ. */ }
                },
                (in, out, next) -> { next.proceed(); throw rejection; }));
        assertSame(rejection, assertThrows(ProxyRejection.class, () -> pipeline.execute(incoming, outgoing)));
    }

    @Test
    void swallowedDoubleProceedViolationStillPreventsForwarding() {
        var pipeline = new ProcessorPipeline(List.of((in, out, next) -> {
            next.proceed();
            try { next.proceed(); }
            catch (IllegalStateException ignored) { /* Нарушение контракта сохраняется после перехвата. */ }
        }));
        assertThrows(IllegalStateException.class, () -> pipeline.execute(incoming, outgoing));
    }

    @Test
    void outerExceptionCannotReplaceTheOriginalRejection() {
        var rejection = new ProxyRejection(401, "Authentication failed");
        var pipeline = new ProcessorPipeline(List.of(
                (in, out, next) -> {
                    try { next.proceed(); }
                    catch (ProxyRejection ignored) { throw new IllegalStateException("Wrapper failed"); }
                },
                (in, out, next) -> { throw rejection; }));
        assertSame(rejection, assertThrows(ProxyRejection.class, () -> pipeline.execute(incoming, outgoing)));
    }

    @Test
    void errorAfterCaughtRejectionCannotBeMaskedByTheRejection() {
        var rejection = new ProxyRejection(403, "Forbidden");
        var fatal = new OutOfMemoryError("Processor failed");
        var pipeline = new ProcessorPipeline(List.of(
                (in, out, next) -> {
                    try { next.proceed(); }
                    catch (ProxyRejection ignored) { throw fatal; }
                },
                (in, out, next) -> { throw rejection; }));
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> pipeline.execute(incoming, outgoing)));
    }

    @Test
    void outerErrorCannotReplaceTheOriginalError() {
        var original = new OutOfMemoryError("Original failure");
        var pipeline = new ProcessorPipeline(List.of(
                (in, out, next) -> {
                    try { next.proceed(); }
                    catch (OutOfMemoryError ignored) { throw new AssertionError("Wrapper failed"); }
                },
                (in, out, next) -> { throw original; }));
        assertSame(original, assertThrows(OutOfMemoryError.class, () -> pipeline.execute(incoming, outgoing)));
    }

    @Test
    void retainedContinuationCannotRunAfterExecutionReturns() throws Exception {
        RequestProcessor.Chain[] retained = new RequestProcessor.Chain[1];
        var pipeline = new ProcessorPipeline(List.of((in, out, next) -> {
            retained[0] = next;
            next.proceed();
        }));
        pipeline.execute(incoming, outgoing);
        assertThrows(IllegalStateException.class, retained[0]::proceed);
    }

    @Test
    void emptyPipelineIsAllowed() {
        assertDoesNotThrow(() -> new ProcessorPipeline(List.of()).execute(incoming, outgoing));
    }
}
