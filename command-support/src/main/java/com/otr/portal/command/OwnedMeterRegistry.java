package com.otr.portal.command;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.noop.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * A metrics-only composite child that remembers the actual meters returned after the user's
 * filters have run. Policy objects and events remain the actual Resilience4j objects.
 */
final class OwnedMeterRegistry extends MeterRegistry {
    // Registrations, not contexts, own meters. Different composites can resolve to the same
    // actual meter after filters; reference counting prevents one from deleting another's meter.
    private static final Map<MeterRegistry, Map<Meter, Ownership>> OWNERS = new IdentityHashMap<>();
    private MeterRegistry actual;
    private final Map<Meter, Meter> registered = new IdentityHashMap<>();
    private boolean disposed;

    OwnedMeterRegistry(MeterRegistry actual) {
        super(Clock.SYSTEM);
        this.actual = actual;
        config().onMeterRemoved(this::release);
    }

    private <M extends Meter> M register(Class<M> api, Meter.Id inputId, Supplier<M> factory, Supplier<M> noop) {
        synchronized (OWNERS) {
            if (disposed) return noop.get();
            var before = actual.getMeters();
            M meter = factory.get();
            var ownerships = OWNERS.computeIfAbsent(actual, ignored -> new IdentityHashMap<>());
            Ownership ownership = ownerships.get(meter);
            if (ownership == null) {
                boolean created = before.stream().noneMatch(existing -> existing == meter);
                ownership = new Ownership(created);
                ownerships.put(meter, ownership);
            }
            M alias = MeterAlias.create(api, inputId, meter, noop.get());
            registered.put(alias, meter);
            ownership.references++;
            return alias;
        }
    }

    private void release(Meter alias) {
        synchronized (OWNERS) {
            Meter meter = registered.remove(alias);
            if (meter == null) return;
            MeterAlias.detach(alias);
            Map<Meter, Ownership> ownerships = OWNERS.get(actual);
            Ownership ownership = ownerships.get(meter);
            if (--ownership.references == 0) {
                ownerships.remove(meter);
                if (ownership.created && actual.getMeters().stream().anyMatch(existing -> existing == meter)) {
                    actual.remove(meter);
                }
            }
            if (ownerships.isEmpty()) OWNERS.remove(actual);
        }
    }

    @Override
    public void close() {
        // Do not call actual.close() or Meter.close(): pre-existing meters belong to the app.
        // Removing local registrations invokes release and drops only this binding's references.
        synchronized (OWNERS) {
            if (disposed) return;
            disposed = true;
            // A factory may have returned an alias before MeterRegistry publishes it in its
            // meter map. Release registrations directly, including that publication window.
            for (Meter alias : java.util.List.copyOf(registered.keySet())) release(alias);
            for (Meter alias : getMeters()) remove(alias);
            actual = null;
        }
    }

    @Override protected <T> Gauge newGauge(Meter.Id id, T obj, ToDoubleFunction<T> f) {
        return register(Gauge.class, id, () -> Gauge.builder(id.getName(), obj, f).tags(id.getTags())
                .description(id.getDescription()).baseUnit(id.getBaseUnit()).register(actual), () -> new NoopGauge(id));
    }

    @Override protected Counter newCounter(Meter.Id id) {
        return register(Counter.class, id, () -> Counter.builder(id.getName()).tags(id.getTags())
                .description(id.getDescription()).baseUnit(id.getBaseUnit()).register(actual), () -> new NoopCounter(id));
    }

    @Override protected Timer newTimer(Meter.Id id, DistributionStatisticConfig config, PauseDetector detector) {
        return register(Timer.class, id, () -> {
            var builder = Timer.builder(id.getName()).tags(id.getTags()).description(id.getDescription())
                    .pauseDetector(detector).publishPercentileHistogram(config.isPercentileHistogram())
                    .publishPercentiles(config.getPercentiles()).percentilePrecision(config.getPercentilePrecision())
                    .distributionStatisticExpiry(config.getExpiry()).distributionStatisticBufferLength(config.getBufferLength());
            if (config.getMinimumExpectedValueAsDouble() != null) builder.minimumExpectedValue(nanos(config.getMinimumExpectedValueAsDouble()));
            if (config.getMaximumExpectedValueAsDouble() != null) builder.maximumExpectedValue(nanos(config.getMaximumExpectedValueAsDouble()));
            if (config.getServiceLevelObjectiveBoundaries() != null) {
                builder.serviceLevelObjectives(Arrays.stream(config.getServiceLevelObjectiveBoundaries()).mapToObj(OwnedMeterRegistry::nanos).toArray(Duration[]::new));
            }
            return builder.register(actual);
        }, () -> new NoopTimer(id));
    }

    @Override protected DistributionSummary newDistributionSummary(Meter.Id id, DistributionStatisticConfig config, double scale) {
        return register(DistributionSummary.class, id, () -> DistributionSummary.builder(id.getName()).tags(id.getTags())
                .description(id.getDescription()).baseUnit(id.getBaseUnit()).scale(scale)
                .publishPercentileHistogram(config.isPercentileHistogram()).publishPercentiles(config.getPercentiles())
                .percentilePrecision(config.getPercentilePrecision()).minimumExpectedValue(config.getMinimumExpectedValueAsDouble())
                .maximumExpectedValue(config.getMaximumExpectedValueAsDouble())
                .serviceLevelObjectives(config.getServiceLevelObjectiveBoundaries())
                .distributionStatisticExpiry(config.getExpiry()).distributionStatisticBufferLength(config.getBufferLength())
                .register(actual), () -> new NoopDistributionSummary(id));
    }

    @Override protected <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj, ToDoubleFunction<T> f) {
        return register(FunctionCounter.class, id, () -> FunctionCounter.builder(id.getName(), obj, f).tags(id.getTags())
                .description(id.getDescription()).baseUnit(id.getBaseUnit()).register(actual), () -> new NoopFunctionCounter(id));
    }

    @Override protected <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj, ToLongFunction<T> count,
                                                           ToDoubleFunction<T> total, TimeUnit unit) {
        return register(FunctionTimer.class, id, () -> FunctionTimer.builder(id.getName(), obj, count, total, unit).tags(id.getTags())
                .description(id.getDescription()).register(actual), () -> new NoopFunctionTimer(id));
    }

    @Override protected Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
        return register(Meter.class, id, () -> Meter.builder(id.getName(), type, measurements).tags(id.getTags())
                .description(id.getDescription()).baseUnit(id.getBaseUnit()).register(actual), () -> new NoopMeter(id));
    }

    @Override protected TimeUnit getBaseTimeUnit() { return TimeUnit.NANOSECONDS; }
    @Override protected DistributionStatisticConfig defaultHistogramConfig() { return DistributionStatisticConfig.NONE; }
    private static Duration nanos(double value) { return Duration.ofNanos((long) value); }

    private static final class Ownership {
        private final boolean created;
        private int references;
        private Ownership(boolean created) { this.created = created; }
    }
}
