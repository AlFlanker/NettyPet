package com.otr.portal.command;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

class ResilienceRegistryConfigTest {
    @Test void springConfigurationWorksWithoutMetricsInfrastructure() {
        try (var context = new AnnotationConfigApplicationContext(ResilienceRegistryConfig.class)) {
            assertNotNull(context.getBean(BulkheadRegistry.class));
            assertEquals(1, context.getBean(RetryRegistry.class).retry("default").getRetryConfig().getMaxAttempts());
            assertNotNull(context.getBean(RegistryMetricsBinding.class));
            assertTrue(context.getBeansOfType(MeterRegistry.class).isEmpty());
        }
    }

    @Test void bindsAllFourDefaultRegistriesAndUpdatesTheirMetrics() {
        try (var meters = new ClosingMeterRegistry(); var context = open(null, meters)) {
            assertMetricsReflectActualObjects(registries(context), meters, "defaults");
        }
    }

    @Test void bindsAllFourSuppliedRegistriesIncludingExistingAndNewEntries() {
        var registries = Registries.create();
        registries.createEntries("before-context");
        try (var meters = new ClosingMeterRegistry(); var context = open(registries, meters)) {
            assertSame(registries.bulkheads, context.getBean(BulkheadRegistry.class));
            assertSame(registries.timers, context.getBean(TimeLimiterRegistry.class));
            assertSame(registries.breakers, context.getBean(CircuitBreakerRegistry.class));
            assertSame(registries.retries, context.getBean(RetryRegistry.class));
            assertEquals(1, context.getBeansOfType(BulkheadRegistry.class).size());
            assertEquals(1, context.getBeansOfType(TimeLimiterRegistry.class).size());
            assertEquals(1, context.getBeansOfType(CircuitBreakerRegistry.class).size());
            assertEquals(1, context.getBeansOfType(RetryRegistry.class).size());
            assertMetricsReflectActualObjects(registries, meters, "before-context");
            assertMetricsReflectActualObjects(registries, meters, "after-context");
            int count = meters.getMeters().size();
            registries.timers.timeLimiter("after-context").onSuccess();
            assertEquals(2, success(meters, "after-context").count());
            assertEquals(count, meters.getMeters().size());
        }
    }

    @Test void overlappingContextsWithSameRegistryAndMeterBindOnlyOnce() {
        var registries = Registries.create();
        try (var meters = new ClosingMeterRegistry(); var first = open(registries, meters)) {
            assertMetricsReflectActualObjects(registries, meters, "shared");
            int count = meters.getMeters().size();
            try (var second = open(registries, meters)) {
                assertEquals(count, meters.getMeters().size());
                registries.timers.timeLimiter("shared").onSuccess();
                assertEquals(2, success(meters, "shared").count());
                first.close();
                assertFalse(meters.getMeters().isEmpty());
                registries.timers.timeLimiter("shared").onSuccess();
                assertEquals(3, success(meters, "shared").count());
            }
            assertTrue(meters.getMeters().isEmpty());
            assertFalse(meters.isClosed());
        }
    }

    @Test void sharedRegistriesSupportDifferentMetersWithIndependentContextLifetimes() {
        var registries = Registries.create();
        try (var left = new ClosingMeterRegistry(); var right = new ClosingMeterRegistry();
             var first = open(registries, left); var second = open(registries, right)) {
            assertMetricsReflectActualObjects(registries, left, "separate");
            assertEquals(1, success(right, "separate").count());
            assertEquals(1, right.get("resilience4j.circuitbreaker.calls").tag("name", "separate").tag("kind", "failed").timer().count());
            first.close();
            assertTrue(left.getMeters().isEmpty());
            registries.timers.timeLimiter("separate").onSuccess();
            assertEquals(2, success(right, "separate").count());
            assertFalse(right.getMeters().isEmpty());
        }
    }

    @Test void closeAndReopenDoesNotRetainOldMetersOrDuplicateEventConsumers() {
        var registries = Registries.create();
        try (var meters = new ClosingMeterRegistry()) {
            Counter unrelated = meters.counter("application.request");
            Counter old;
            try (var first = open(registries, meters)) {
                assertMetricsReflectActualObjects(registries, meters, "reopen");
                old = success(meters, "reopen");
            }
            assertEquals(1, meters.getMeters().size());
            assertSame(unrelated, meters.get("application.request").counter());
            registries.timers.timeLimiter("reopen").onSuccess();
            registries.createEntries("created-while-closed");
            assertEquals(1, old.count());
            assertEquals(1, meters.getMeters().size());
            try (var second = open(registries, meters)) {
                assertNotSame(old, success(meters, "reopen"));
                registries.timers.timeLimiter("reopen").onSuccess();
                assertEquals(1, success(meters, "reopen").count());
                assertMetricsReflectActualObjects(registries, meters, "created-while-closed");
                assertEquals(1, old.count());
            }
            assertEquals(1, meters.getMeters().size());
        }
    }

    @Test void removalAndReplacementFollowActualRegistryEvents() {
        var registries = Registries.create();
        try (var meters = new ClosingMeterRegistry(); var context = open(registries, meters)) {
            assertMetricsReflectActualObjects(registries, meters, "removed");
            registries.removeEntries("removed");
            assertTrue(meters.getMeters().isEmpty());
            assertMetricsReflectActualObjects(registries, meters, "removed");
            assertEquals(1, success(meters, "removed").count());
        }
    }

    @Test void lifecycleRemovalPreservesPreexistingMetersAfterCommonTagsAndNameFilters() {
        var registries = Registries.create();
        try (var meters = new ClosingMeterRegistry()) {
            meters.config().commonTags("service", "test").meterFilter(new MeterFilter() {
                @Override public Meter.Id map(Meter.Id id) { return id.withName("renamed." + id.getName()); }
            });
            meters.counter("application.request");
            Counter preexisting = Counter.builder("resilience4j.timelimiter.calls")
                    .tags("name", "filtered", "kind", "successful").register(meters);
            try (var context = open(registries, meters)) {
                registries.createEntries("filtered");
                registries.timers.timeLimiter("filtered").onSuccess();
                assertEquals(1, preexisting.count());
                assertNotNull(meters.get("renamed.resilience4j.bulkhead.available.concurrent.calls").tag("name", "filtered").gauge());
            }
            assertEquals(2, meters.getMeters().size());
            assertNotNull(meters.get("renamed.application.request").counter());
            assertSame(preexisting, meters.get("renamed.resilience4j.timelimiter.calls").tag("name", "filtered").tag("kind", "successful").counter());
            registries.timers.timeLimiter("filtered").onSuccess();
            assertEquals(1, preexisting.count());
        }
    }

    @Test void closingOneBindingCannotDeleteAnotherBindingMeterAfterIdCollision() {
        var left = Registries.create();
        var right = Registries.create();
        try (var meters = new ClosingMeterRegistry(); var first = open(left, meters); var second = open(right, meters)) {
            left.createEntries("same-id");
            right.createEntries("same-id");
            left.timers.timeLimiter("same-id").onSuccess();
            right.timers.timeLimiter("same-id").onSuccess();
            assertEquals(2, success(meters, "same-id").count());
            first.close();
            right.timers.timeLimiter("same-id").onSuccess();
            assertEquals(3, success(meters, "same-id").count());
            assertNotNull(meters.get("resilience4j.bulkhead.available.concurrent.calls").tag("name", "same-id").gauge());
        }
    }

    @Test void collapsedProfileTagsKeepEachLocalAliasUntilItsOwnRemoval() {
        var registries = Registries.create();
        try (var meters = new ClosingMeterRegistry(); var context = open(registries, meters)) {
            meters.config().meterFilter(MeterFilter.ignoreTags("name"));
            Counter preexisting = Counter.builder("resilience4j.timelimiter.calls").tag("kind", "failed").register(meters);
            var left = registries.timers.timeLimiter("left");
            var right = registries.timers.timeLimiter("right");
            left.onSuccess();
            right.onSuccess();
            Counter aggregate = meters.get("resilience4j.timelimiter.calls").tag("kind", "successful").counter();
            assertEquals(2, aggregate.count());
            registries.timers.remove("left");
            assertSame(aggregate, meters.get("resilience4j.timelimiter.calls").tag("kind", "successful").counter());
            right.onSuccess();
            assertEquals(3, aggregate.count());

            var recreated = registries.timers.timeLimiter("left");
            recreated.onSuccess();
            assertEquals(4, aggregate.count());
            registries.timers.remove("right");
            assertSame(aggregate, meters.get("resilience4j.timelimiter.calls").tag("kind", "successful").counter());
            recreated.onSuccess();
            assertEquals(5, aggregate.count());
            registries.timers.remove("left");
            assertNull(meters.find("resilience4j.timelimiter.calls").tag("kind", "successful").counter());
            assertEquals(1, meters.getMeters().size());
            assertSame(preexisting, meters.get("resilience4j.timelimiter.calls").tag("kind", "failed").counter());
        }
    }

    @Test void contextCloseDuringCompositePublicationDetachesActualRegistryAndMeter() throws Exception {
        var registries = Registries.create();
        var worker = Executors.newSingleThreadExecutor();
        var releasePublication = new CountDownLatch(1);
        try (var meters = new ClosingMeterRegistry(); var context = open(registries, meters)) {
            Counter preexisting = Counter.builder("resilience4j.timelimiter.calls")
                    .tags("name", "racing", "kind", "successful").register(meters);
            var shared = (Map<?, ?>) field(null, RegistryMetricsBinding.class, "SHARED");
            Object binding = shared.get(registries.timers);
            var composite = (CompositeMeterRegistry) field(binding, binding.getClass(), "composite");
            var child = (OwnedMeterRegistry) composite.getRegistries().iterator().next();
            var attachedBeforePublication = new CountDownLatch(1);
            composite.config().onMeterAdded(metric -> {
                if (!"successful".equals(metric.getId().getTag("kind"))) return;
                attachedBeforePublication.countDown();
                try {
                    if (!releasePublication.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("publication latch timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
            var created = worker.submit(() -> registries.timers.timeLimiter("racing"));
            try {
                assertTrue(attachedBeforePublication.await(5, TimeUnit.SECONDS));
                assertTrue(composite.getMeters().isEmpty(), "child is attached before parent meter publication");
                Meter alias = child.getMeters().getFirst();
                context.close();
                assertNull(field(child, OwnedMeterRegistry.class, "actual"));
                Object handler = Proxy.getInvocationHandler(alias);
                assertNotSame(preexisting, field(handler, MeterAlias.class, "delegate"));
                assertTrue(child.getMeters().isEmpty());
                assertSame(preexisting, meters.get("resilience4j.timelimiter.calls").tag("kind", "successful").counter());
                releasePublication.countDown();
                created.get(5, TimeUnit.SECONDS).onSuccess();
                assertEquals(0, preexisting.count(), "late publication must not update a detached actual meter");
                assertTrue(composite.getRegistries().isEmpty());
            } finally {
                releasePublication.countDown();
            }
        } finally {
            releasePublication.countDown();
            worker.shutdownNow();
        }
    }

    private static Object field(Object target, Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void assertMetricsReflectActualObjects(Registries registries, MeterRegistry meters, String name) {
        registries.createEntries(name);
        var bulkhead = registries.bulkheads.bulkhead(name);
        var available = meters.get("resilience4j.bulkhead.available.concurrent.calls").tag("name", name).gauge();
        assertEquals(1, available.value());
        assertTrue(bulkhead.tryAcquirePermission());
        assertEquals(0, available.value());
        bulkhead.onComplete();
        assertEquals(1, available.value());

        var breaker = registries.breakers.circuitBreaker(name);
        breaker.onError(5, TimeUnit.MILLISECONDS, new IllegalStateException("upstream"));
        assertEquals(1, meters.get("resilience4j.circuitbreaker.state").tag("name", name).tag("state", "open").gauge().value());
        assertEquals(1, meters.get("resilience4j.circuitbreaker.calls").tag("name", name).tag("kind", "failed").timer().count());

        var limiter = registries.timers.timeLimiter(name);
        limiter.onSuccess();
        limiter.onError(new TimeoutException("deadline"));
        assertEquals(1, success(meters, name).count());
        assertEquals(1, meters.get("resilience4j.timelimiter.calls").tag("name", name).tag("kind", "timeout").counter().count());

        Retry.decorateSupplier(registries.retries.retry(name), () -> "done").get();
        assertEquals(1, meters.get("resilience4j.retry.calls").tag("name", name).tag("kind", "successful_without_retry").functionCounter().count());
    }

    private static Counter success(MeterRegistry meters, String name) {
        return meters.get("resilience4j.timelimiter.calls").tag("name", name).tag("kind", "successful").counter();
    }

    private static AnnotationConfigApplicationContext open(Registries registries, MeterRegistry meters) {
        var context = new AnnotationConfigApplicationContext();
        if (registries != null) {
            context.getBeanFactory().registerSingleton("customBulkheads", registries.bulkheads);
            context.getBeanFactory().registerSingleton("customTimers", registries.timers);
            context.getBeanFactory().registerSingleton("customBreakers", registries.breakers);
            context.getBeanFactory().registerSingleton("customRetries", registries.retries);
        }
        // These instances belong to the caller; closing a context must only detach its binding.
        context.getBeanFactory().registerSingleton("customMeters", meters);
        context.register(ResilienceRegistryConfig.class);
        context.refresh();
        return context;
    }

    private static Registries registries(AnnotationConfigApplicationContext context) {
        return new Registries(context.getBean(BulkheadRegistry.class), context.getBean(TimeLimiterRegistry.class),
                context.getBean(CircuitBreakerRegistry.class), context.getBean(RetryRegistry.class));
    }

    private static final class ClosingMeterRegistry extends SimpleMeterRegistry implements AutoCloseable { }

    private record Registries(BulkheadRegistry bulkheads, TimeLimiterRegistry timers,
                              CircuitBreakerRegistry breakers, RetryRegistry retries) {
        static Registries create() {
            return new Registries(BulkheadRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(),
                    CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
        }
        void createEntries(String name) {
            bulkheads.bulkhead(name, BulkheadConfig.custom().maxConcurrentCalls(1).build());
            timers.timeLimiter(name);
            breakers.circuitBreaker(name, CircuitBreakerConfig.custom().minimumNumberOfCalls(1).slidingWindowSize(2).build());
            retries.retry(name);
        }
        void removeEntries(String name) {
            bulkheads.remove(name);
            timers.remove(name);
            breakers.remove(name);
            retries.remove(name);
        }
    }
}
