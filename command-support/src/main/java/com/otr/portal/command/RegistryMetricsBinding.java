package com.otr.portal.command;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.Registry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Context-owned subscription to the actual Resilience4j registries' official metrics.
 *
 * <p>Resilience4j 2.4 event publishers cannot unsubscribe. Install each official binder only
 * once per actual registry, targeting a composite with context-owned children. Closing a
 * context detaches its child and removes its meters; it neither closes a supplied meter
 * registry nor adds another set of primitive/registry listeners on a subsequent context.
 * All state shared across contexts is guarded by {@code SHARED}. Independent registries
 * sharing a meter registry must use distinct profile names/tags; identical Micrometer IDs
 * have the usual first-gauge-source semantics, even though their ownership is reference counted.
 */
final class RegistryMetricsBinding implements AutoCloseable {
    // Values must never retain their weak registry keys. Official gauges/function counters
    // weakly reference their policy objects; counters/timers have no registry reference.
    // The official binder, which DOES retain its registry, is not stored in this map.
    private static final Map<Registry<?, ?>, SharedBinding> SHARED = new WeakHashMap<>();
    private final List<Registration> registrations = new ArrayList<>();

    RegistryMetricsBinding() { }

    RegistryMetricsBinding(MeterRegistry meters, BulkheadRegistry bulkheads, TimeLimiterRegistry timers,
                           CircuitBreakerRegistry breakers, RetryRegistry retries) {
        try {
            add(bulkheads, meters, target -> TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(target));
            add(timers, meters, target -> TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(timers).bindTo(target));
            add(breakers, meters, target -> TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(target));
            add(retries, meters, target -> TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(target));
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void add(Registry<?, ?> registry, MeterRegistry meters, Consumer<MeterRegistry> binder) {
        synchronized (SHARED) {
            SharedBinding shared = SHARED.get(registry);
            if (shared == null) {
                shared = new SharedBinding();
                shared.attach(meters);
                try {
                    binder.accept(shared.composite);
                } catch (RuntimeException | Error failure) {
                    shared.detach(meters);
                    throw failure;
                }
                SHARED.put(registry, shared);
            } else {
                shared.attach(meters);
            }
            registrations.add(new Registration(shared, meters));
        }
    }

    @Override
    public void close() {
        synchronized (SHARED) {
            for (Registration registration : registrations) registration.binding.detach(registration.meters);
            registrations.clear();
        }
    }

    private record Registration(SharedBinding binding, MeterRegistry meters) { }

    private static final class SharedBinding {
        private final CompositeMeterRegistry composite = new CompositeMeterRegistry();
        private final Map<MeterRegistry, Child> children = new IdentityHashMap<>();

        private void attach(MeterRegistry meters) {
            Child existing = children.get(meters);
            if (existing != null) {
                existing.references++;
                return;
            }
            Child child = new Child(new OwnedMeterRegistry(meters));
            composite.add(child.owned);
            children.put(meters, child);
        }

        private void detach(MeterRegistry meters) {
            Child child = children.get(meters);
            if (child == null || --child.references != 0) return;
            composite.remove(child.owned);
            children.remove(meters);
            child.owned.close();
        }
    }

    private static final class Child {
        private final OwnedMeterRegistry owned;
        private int references = 1;
        private Child(OwnedMeterRegistry owned) { this.owned = owned; }
    }
}
