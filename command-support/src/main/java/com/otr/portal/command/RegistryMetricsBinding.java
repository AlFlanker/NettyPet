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
 * Привязывает метрики фактических реестров Resilience4j на время жизни Spring-контекста.
 *
 * <p>Resilience4j 2.4 не позволяет отписывать обработчики событий. Поэтому стандартная привязка
 * устанавливается один раз на реестр и пишет в составной реестр, к которому контексты подключают
 * собственные дочерние реестры. Закрытие контекста удаляет его привязку и принадлежащие ей метрики,
 * не закрывая пользовательский MeterRegistry. Повторное подключение не дублирует обработчики событий.
 * Общее состояние защищено монитором {@code SHARED}. Независимые реестры должны различать
 * имена или теги профилей: при одинаковом ID Micrometer использует первый источник Gauge,
 * даже если ссылки на общую метрику учитываются корректно.
 */
final class RegistryMetricsBinding implements AutoCloseable {
    // Значение не должно удерживать реестр, служащий слабым ключом этой карты.
    // Gauge и функциональные счётчики ссылаются на политики слабо, обычные счётчики и таймеры
    // не удерживают реестр. Сам объект стандартной привязки, который удерживает реестр, здесь не храним.
    private static final Map<Registry<?, ?>, SharedBinding> SHARED = new WeakHashMap<>();
    private final List<Registration> registrations = new ArrayList<>();

    RegistryMetricsBinding() { }

    RegistryMetricsBinding(MeterRegistry meters, BulkheadRegistry bulkheads, TimeLimiterRegistry timers,
                           CircuitBreakerRegistry breakers, RetryRegistry retries) {
        try {
            add(bulkheads, meters, target ->
                    TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(target));
            add(timers, meters, target ->
                    TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(timers).bindTo(target));
            add(breakers, meters, target ->
                    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(target));
            add(retries, meters, target ->
                    TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(target));
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    /**
     * Переиспользует общую привязку событий и фиксирует подключение данного контекста.
     * При ошибке создания отключает добавленный дочерний реестр, не оставляя частичную привязку.
     */
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

    /** Снимает только ссылки этого контекста; общие обработчики событий реестра остаются для следующих привязок. */
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

        /** Подключает дочерний реестр один раз и учитывает совместное использование несколькими контекстами. */
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

        /** При последней ссылке отключает дочерний реестр и освобождает принадлежащие привязке метрики. */
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
