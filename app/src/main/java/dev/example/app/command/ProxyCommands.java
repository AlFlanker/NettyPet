package dev.example.app.command;

import com.otr.portal.command.CommandConfigRegistry;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.model.CommandContext;
import com.otr.portal.command.model.CommandResilienceHolder;
import dev.example.proxy.transport.ProxyEngine;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Application-scoped policy and diagnostics for the one-shot streaming proxy command. */
public final class ProxyCommands implements AutoCloseable {
    static final String NAME = "proxy";
    private final CommandResilienceDecorator decorator;
    private final CommandProperties properties;
    private final CommandResilienceHolder holder;
    private final CommandConfigRegistry.Lease policyLease;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ProxyCommands(CommandConfigRegistry registry, CommandResilienceDecorator decorator,
                         CommandProperties properties) {
        this.decorator = Objects.requireNonNull(decorator, "decorator");
        this.properties = Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(registry, "registry");
        // Pin the same policy generation for both commands and diagnostics. Registry
        // resets must fail while this facade is alive, including when traffic is idle.
        this.policyLease = registry.acquire(CommandContext.builder()
                .commandName(NAME).properties(properties).build());
        this.holder = policyLease.holder();
    }

    public ProxyRequestCommand command(ProxyEngine engine, HttpServletRequest request,
                                       HttpServletResponse response, String rawPath) {
        return new ProxyRequestCommand(decorator, properties, engine, request, response, rawPath);
    }

    public Bulkhead bulkhead() { return holder.getBulkhead(); }
    public CircuitBreaker circuitBreaker() { return holder.getCircuitBreaker(); }
    public TimeLimiter timeLimiter() { return holder.getTimeLimiter(); }

    /** Request cancellation before the transport is shut down; never wait on I/O threads. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            decorator.close();
        } finally {
            // Each active execution retains its own lease until actual I/O cleanup.
            policyLease.close();
        }
    }
}
