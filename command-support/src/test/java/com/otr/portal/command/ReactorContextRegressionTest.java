package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.exception.RetryableException;
import com.otr.portal.command.execution.CommandTask;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ReactorContextRegressionTest {
    private static final String TRACE = "traceId";

    @Test void reusablePublisherCapturesContextSeparatelyForEverySubscription() {
        try (Fixture f = new Fixture(policy(false))) {
            AtomicInteger factories = new AtomicInteger();
            var publisher = f.command(() -> {
                factories.incrementAndGet();
                return Mono.deferContextual(context -> Mono.just(context.getOrDefault(TRACE, "absent")));
            }, Mono::error).execute();
            assertEquals(0, factories.get());
            assertEquals("left", publisher.contextWrite(Context.of(TRACE, "left")).block());
            assertEquals("right", publisher.contextWrite(Context.of(TRACE, "right")).block());
            assertEquals("absent", publisher.block(), "Context must not leak from an earlier subscriber");
            assertEquals(3, factories.get());
        }
    }

    @Test void concurrentSubscribersKeepDistinctContextsAcrossAsynchronousSourceSignals() throws Exception {
        try (Fixture f = new Fixture(policy(false))) {
            CountDownLatch subscribed = new CountDownLatch(2);
            Sinks.One<Boolean> release = Sinks.one();
            var publisher = f.command(() -> Mono.deferContextual(context -> {
                String trace = context.get(TRACE);
                subscribed.countDown();
                return release.asMono().map(ignored -> trace);
            }), Mono::error).execute();
            var left = publisher.contextWrite(Context.of(TRACE, "left"))
                    .subscribeOn(Schedulers.parallel()).toFuture();
            var right = publisher.contextWrite(Context.of(TRACE, "right"))
                    .subscribeOn(Schedulers.parallel()).toFuture();
            assertTrue(subscribed.await(3, TimeUnit.SECONDS));
            assertEquals(Sinks.EmitResult.OK, release.tryEmitValue(true));
            assertEquals("left", left.get(3, TimeUnit.SECONDS));
            assertEquals("right", right.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void retryAttemptsInheritTheSameContextWithoutLeakingIntoTheNextSubscription() {
        try (Fixture f = new Fixture(policy(true))) {
            AtomicInteger attempts = new AtomicInteger();
            List<String> seen = new CopyOnWriteArrayList<>();
            var publisher = f.command(() -> Mono.deferContextual(context -> {
                String trace = context.get(TRACE);
                seen.add(trace);
                return attempts.incrementAndGet() % 2 == 1
                        ? Mono.error(new RetryableException("transient")) : Mono.just(trace);
            }), Mono::error).execute();
            assertEquals("first", publisher.contextWrite(Context.of(TRACE, "first")).block());
            assertEquals("second", publisher.contextWrite(Context.of(TRACE, "second")).block());
            assertEquals(List.of("first", "first", "second", "second"), seen);
        }
    }

    @Test void fallbackRetainsContextWhenSourceFactoryThrowsSynchronously() {
        try (Fixture f = new Fixture(policy(false))) {
            var publisher = f.command(() -> { throw new IllegalArgumentException("factory"); },
                    failure -> Mono.deferContextual(context -> Mono.just("fallback-" + context.get(TRACE))))
                    .execute();
            assertEquals("fallback-first", publisher.contextWrite(Context.of(TRACE, "first")).block());
            assertEquals("fallback-second", publisher.contextWrite(Context.of(TRACE, "second")).block());
        }
    }

    @Test void fallbackRetainsContextWhenTheTotalDeadlineExpiresOnTheTimerThread() {
        var properties = policy(false).toBuilder().timeout(Duration.ofMillis(80)).build();
        try (Fixture f = new Fixture(properties)) {
            var publisher = f.command(Mono::never,
                    failure -> Mono.deferContextual(context -> Mono.just("fallback-" + context.get(TRACE))))
                    .execute();
            assertEquals("fallback-first", publisher.contextWrite(Context.of(TRACE, "first")).block());
            assertEquals("fallback-second", publisher.contextWrite(Context.of(TRACE, "second")).block());
        }
    }

    @Test void fallbackRetainsContextAfterRetriesAreExhaustedOnTheRetryWorker() {
        try (Fixture f = new Fixture(policy(true))) {
            List<String> seen = new CopyOnWriteArrayList<>();
            var publisher = f.command(() -> Mono.deferContextual(context -> {
                seen.add(context.get(TRACE));
                return Mono.error(new RetryableException("persistent"));
            }), failure -> Mono.deferContextual(context -> Mono.just("fallback-" + context.get(TRACE))))
                    .execute();
            assertEquals("fallback-request", publisher.contextWrite(Context.of(TRACE, "request")).block());
            assertEquals(List.of("request", "request"), seen);
        }
    }

    @Test void resourceCommandFallbackStillUsesTheReactorSubscribersContext() {
        try (Fixture f = new Fixture(policy(false))) {
            var publisher = new AbstractResourceCommand<String>(f.decorator, f.properties) {
                protected String getCommandName() { return "context"; }
                protected CommandTask<String> doStart() { throw new IllegalArgumentException("resource factory"); }
                protected Mono<String> fallbackOnError(Throwable failure) {
                    return Mono.deferContextual(context -> Mono.just(context.get(TRACE)));
                }
            }.execute();
            assertEquals("resource-fallback", publisher.contextWrite(Context.of(TRACE, "resource-fallback")).block());
        }
    }

    private static CommandProperties policy(boolean retry) {
        return CommandProperties.builder().timeout(Duration.ofSeconds(3)).maxConcurrency(2)
                .maxAttempts(retry ? 2 : 1).replayable(retry).retryWaitDuration(Duration.ZERO).build();
    }

    private static final class Fixture implements AutoCloseable {
        final CommandProperties properties;
        final CommandResilienceDecorator decorator;
        Fixture(CommandProperties properties) {
            this.properties = properties;
            decorator = new CommandResilienceDecorator(new CommandConfigRegistry(BulkheadRegistry.ofDefaults(),
                    TimeLimiterRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults()));
        }
        AbstractResilienceCommand<String> command(Supplier<Mono<String>> source,
                                                   Function<Throwable, Mono<String>> fallback) {
            return new AbstractResilienceCommand<>(decorator, properties) {
                protected String getCommandName() { return "context"; }
                protected Mono<String> doExecute() { return source.get(); }
                protected Mono<String> fallbackOnError(Throwable failure) { return fallback.apply(failure); }
                protected void logResilienceError(Throwable failure, String name) { }
            };
        }
        public void close() { decorator.close(); }
    }
}
