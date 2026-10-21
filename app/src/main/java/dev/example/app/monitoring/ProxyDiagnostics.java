package dev.example.app.monitoring;

import dev.example.proxy.api.ProxyResult;
import dev.example.proxy.transport.ProxyEngine;
import dev.example.resilience.CommandExecutor;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;

/**
 * Read-only адаптер наблюдаемости находится в app — в точке композиции транспорта
 * и resilience. Он не добавляет HTTP endpoint, удалённый JMX connector или backend probe.
 *
 * <p>Набор scalar attributes фиксирован, без URI/клиентских labels. Обработчики R4j
 * лишь увеличивают LongAdder: они не блокируют I/O/timer threads и не сохраняют события.
 * Для каждого ApplicationContext создаётся свой MBean с identity в ObjectName;
 * Spring exporter удаляет его при остановке контекста.</p>
 *
 * <p>Поля читаются независимо, не являются общим атомарным snapshot. Счётчики byte/duration
 * относятся к завершённым proxy exchange; duration включает cleanup. JMX не заменяет
 * внешний замер p99, RSS/direct memory и результата получения тела клиентом.</p>
 */
@Component
@ConditionalOnProperty(name = "spring.jmx.enabled", havingValue = "true")
@ManagedResource(objectName = "dev.example.proxy:type=Diagnostics",
        description = "Read-only proxy and resilience diagnostics; readiness is local, not upstream health")
public final class ProxyDiagnostics {
    private final ProxyEngine engine;
    private final CommandExecutor<ProxyResult> commands;
    private final ApplicationAvailability availability;
    private final LongAdder bulkheadRejected = new LongAdder();
    private final LongAdder circuitRejected = new LongAdder();
    private final LongAdder timeLimiterSuccess = new LongAdder();
    private final LongAdder timeLimiterErrors = new LongAdder();
    private final LongAdder timeLimiterTimeouts = new LongAdder();

    public ProxyDiagnostics(ProxyEngine engine, CommandExecutor<ProxyResult> commands,
                            ApplicationAvailability availability) {
        this.engine = engine;
        this.commands = commands;
        this.availability = availability;
        commands.bulkhead().getEventPublisher().onCallRejected(ignored -> bulkheadRejected.increment());
        commands.circuitBreaker().getEventPublisher().onCallNotPermitted(ignored -> circuitRejected.increment());
        commands.timeLimiter().getEventPublisher()
                .onSuccess(ignored -> timeLimiterSuccess.increment())
                .onError(ignored -> timeLimiterErrors.increment())
                .onTimeout(ignored -> timeLimiterTimeouts.increment());
    }

    @ManagedAttribute(description = "Spring lifecycle readiness; no network probe or upstream health claim")
    public String getLocalReadiness() { return availability.getReadinessState().name(); }

    @ManagedAttribute(description = "Spring lifecycle liveness state")
    public String getLocalLiveness() { return availability.getLivenessState().name(); }

    @ManagedAttribute(description = "Local readiness accepts traffic; independent of circuit state and backend health")
    public boolean isAcceptingTraffic() {
        return availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
    }

    @ManagedAttribute(description = "Actually occupied proxy permits, including cleanup and pending acquire")
    public int getActiveProxyRequests() { return engine.activeRequests(); }

    @ManagedAttribute(description = "Proxy admission permits obtained since startup")
    public long getProxyAdmittedTotal() { return engine.metrics().admitted(); }

    @ManagedAttribute(description = "Proxy preparation/admission rejections; excludes rejection before proxy by R4j")
    public long getProxyRejectedTotal() { return engine.metrics().rejected(); }

    @ManagedAttribute(description = "All completed proxy exchanges including errors and cancellation")
    public long getProxyCompletedTotal() { return engine.metrics().completed(); }

    @ManagedAttribute(description = "Completed proxy cancellations, including externally requested cancellation")
    public long getProxyCancelledTotal() { return engine.metrics().cancelled(); }

    @ManagedAttribute(description = "Proxy failures with diagnostic status 5xx; wire status may already be committed")
    public long getProxyFailedTotal() { return engine.metrics().failed(); }

    @ManagedAttribute(description = "Request body bytes read for completed exchanges")
    public long getUploadedBytesTotal() { return engine.metrics().totalUploadBytes(); }

    @ManagedAttribute(description = "Response body bytes submitted for completed exchanges; not TCP acknowledgement")
    public long getDownloadedBytesTotal() { return engine.metrics().totalDownloadBytes(); }

    @ManagedAttribute(description = "Sum of completed exchange durations including cleanup; not a latency histogram")
    public long getExchangeDurationNanosTotal() { return engine.metrics().totalDurationNanos(); }

    @ManagedAttribute(description = "Historical maximum queued response payload among completed exchanges")
    public long getMaxQueuedResponseBytes() { return engine.metrics().maxQueuedResponseBytes(); }

    @ManagedAttribute(description = "Currently available semaphore Bulkhead permits")
    public int getBulkheadAvailableCalls() { return commands.bulkhead().getMetrics().getAvailableConcurrentCalls(); }

    @ManagedAttribute(description = "Configured semaphore Bulkhead concurrency limit")
    public int getBulkheadMaxCalls() { return commands.bulkhead().getMetrics().getMaxAllowedConcurrentCalls(); }

    @ManagedAttribute(description = "Bulkhead rejection events since diagnostics initialization")
    public long getBulkheadRejectedTotal() { return bulkheadRejected.sum(); }

    @ManagedAttribute(description = "Current R4j circuit state; deliberately separate from local readiness")
    public String getCircuitBreakerState() { return commands.circuitBreaker().getState().name(); }

    @ManagedAttribute(description = "Successful calls in the current circuit measurement window; may reset")
    public int getCircuitBreakerWindowSuccessfulCalls() {
        return commands.circuitBreaker().getMetrics().getNumberOfSuccessfulCalls();
    }

    @ManagedAttribute(description = "Failed calls in the current circuit measurement window; may reset")
    public int getCircuitBreakerWindowFailedCalls() {
        return commands.circuitBreaker().getMetrics().getNumberOfFailedCalls();
    }

    @ManagedAttribute(description = "Failure rate in current circuit window; -1 means insufficient calls")
    public float getCircuitBreakerFailureRate() { return commands.circuitBreaker().getMetrics().getFailureRate(); }

    @ManagedAttribute(description = "Circuit rejection events since diagnostics initialization")
    public long getCircuitBreakerRejectedTotal() { return circuitRejected.sum(); }

    @ManagedAttribute(description = "TimeLimiter success events since diagnostics initialization")
    public long getTimeLimiterSuccessTotal() { return timeLimiterSuccess.sum(); }

    @ManagedAttribute(description = "TimeLimiter non-timeout error events since diagnostics initialization")
    public long getTimeLimiterErrorsTotal() { return timeLimiterErrors.sum(); }

    @ManagedAttribute(description = "TimeLimiter timeout events since diagnostics initialization")
    public long getTimeLimiterTimeoutsTotal() { return timeLimiterTimeouts.sum(); }
}
