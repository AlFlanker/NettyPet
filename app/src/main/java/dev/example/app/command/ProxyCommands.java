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

/** Хранит общую политику потоковых команд прокси и используемые диагностикой лимитеры. */
public final class ProxyCommands implements AutoCloseable {
    static final String NAME = "proxy";
    private final CommandResilienceDecorator decorator;
    private final CommandProperties properties;
    private final CommandResilienceHolder holder;
    private final CommandConfigRegistry.Lease policyLease;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Создаёт общую политику и удерживает её поколение до закрытия фасада. */
    public ProxyCommands(CommandConfigRegistry registry, CommandResilienceDecorator decorator,
                         CommandProperties properties) {
        this.decorator = Objects.requireNonNull(decorator, "decorator");
        this.properties = Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(registry, "registry");
        // Удерживаем одно поколение политики для команд и диагностики. Пока фасад работает,
        // сброс реестра запрещён даже без запросов: наблюдатели должны видеть те же лимитеры.
        this.policyLease = registry.acquire(CommandContext.builder()
                .commandName(NAME).properties(properties).build());
        this.holder = policyLease.holder();
    }

    /** Создаёт одноразовую команду запроса без запуска I/O; работа начнётся после подписки. */
    public ProxyRequestCommand command(ProxyEngine engine, HttpServletRequest request,
                                       HttpServletResponse response, String rawPath) {
        return new ProxyRequestCommand(decorator, properties, engine, request, response, rawPath);
    }

    public Bulkhead bulkhead() { return holder.getBulkhead(); }
    public CircuitBreaker circuitBreaker() { return holder.getCircuitBreaker(); }
    public TimeLimiter timeLimiter() { return holder.getTimeLimiter(); }

    /** Запрашивает отмену команд перед остановкой транспорта, не ожидая сетевой очистки. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            decorator.close();
        } finally {
            // Активные выполнения сохраняют собственные ссылки на поколение до освобождения ресурсов I/O.
            policyLease.close();
        }
    }
}
