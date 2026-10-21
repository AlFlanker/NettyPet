package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.exception.RetryableException;
import com.otr.portal.command.model.CommandContext;
import com.otr.portal.command.model.CommandResilienceHolder;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Хранит политики по имени команды и запрещает их замену до очистки всех использующих их операций.
 * Обработчики событий Resilience4j должны быть неблокирующими и не бросать исключений.
 * Созданные здесь политики нельзя удалять или заменять напрямую во внешних реестрах Resilience4j.
 */
@Component
@RequiredArgsConstructor
public class CommandConfigRegistry {
    private final BulkheadRegistry bulkheadRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final Map<String, Entry> commandConfigs = new HashMap<>();

    /**
     * Фиксирует поколение политик на время допуска, повторов, отмены и очистки одной операции.
     * Возвращённую ссылку нужно закрыть только после фактического освобождения её ресурсов.
     */
    public synchronized Lease acquire(CommandContext context) {
        Objects.requireNonNull(context, "context");
        Entry entry = resolve(context);
        entry.active++;
        return new Lease(entry);
    }

    /**
     * Возвращает политики без фиксации их времени жизни; сохранён для внутренней совместимости.
     * Исполнители обязаны использовать {@link #acquire(CommandContext)}, чтобы запретить преждевременную замену.
     */
    synchronized CommandResilienceHolder getOrCreate(CommandContext context) {
        return resolve(Objects.requireNonNull(context, "context")).holder;
    }

    /**
     * Удаляет все созданные здесь политики, только если ни одна не используется.
     * При занятой записи метод бросает исключение до удаления любой из записей.
     */
    public synchronized void clearAllCaches() {
        clearMatching(name -> true);
    }

    /**
     * Удаляет собственные политики, имена которых начинаются с указанного префикса.
     * Если хотя бы одна из них используется, ничего не удаляет и бросает исключение.
     * Завершение результата по таймауту не делает политику свободной до окончания очистки.
     */
    public synchronized void clearCache(String startsWith) {
        Objects.requireNonNull(startsWith, "startsWith");
        clearMatching(name -> name.startsWith(startsWith));
    }

    /**
     * Находит неизменяемые политики либо создаёт их для нового имени.
     * Другие настройки существующего имени отклоняются, чтобы не разделить работающие вызовы
     * между старым и новым ограничителем параллелизма.
     */
    private Entry resolve(CommandContext context) {
        Entry existing = commandConfigs.get(context.getCommandName());
        if (existing != null) {
            if (!existing.properties.equals(context.getProperties())) {
                throw new IllegalArgumentException("Policy for command '" + context.getCommandName()
                        + "' already exists; clear it while idle before changing its configuration");
            }
            return existing;
        }
        String name = context.getCommandName();
        // Чужой лимитер с тем же именем может иметь другие настройки: не принимаем его неявно.
        if (bulkheadRegistry.find(name).isPresent() || timeLimiterRegistry.find(name).isPresent()
                || circuitBreakerRegistry.find(name).isPresent() || retryRegistry.find(name).isPresent()) {
            throw new IllegalStateException("Resilience registry already contains unmanaged command '" + name + "'");
        }
        CommandProperties properties = context.getProperties();
        var holder = CommandResilienceHolder.builder()
                .bulkhead(bulkheadRegistry.bulkhead(name, BulkheadConfig.custom()
                        .maxConcurrentCalls(properties.getMaxConcurrency()).maxWaitDuration(Duration.ZERO).build()))
                .timeLimiter(timeLimiterRegistry.timeLimiter(name, TimeLimiterConfig.custom()
                        .timeoutDuration(properties.getTimeout()).cancelRunningFuture(true).build()))
                .circuitBreaker(circuitBreakerRegistry.circuitBreaker(name, properties.getCircuitBreakerConfig()))
                .retry(retryRegistry.retry(name, RetryConfig.custom()
                        .maxAttempts(properties.getMaxAttempts())
                        .waitDuration(properties.getRetryWaitDuration())
                        .retryExceptions(RetryableException.class).build()))
                .build();
        Entry created = new Entry(properties, holder);
        commandConfigs.put(name, created);
        return created;
    }

    /**
     * Сначала проверяет все выбранные записи, затем удаляет их из четырёх реестров.
     * Два прохода под общим монитором исключают частичную очистку при занятой записи.
     */
    private void clearMatching(Predicate<String> matches) {
        commandConfigs.forEach((name, entry) -> {
            if (matches.test(name) && entry.active != 0) {
                throw new IllegalStateException("Command '" + name + "' still has active operations");
            }
        });
        var names = commandConfigs.keySet().stream().filter(matches).toList();
        names.forEach(name -> {
            bulkheadRegistry.remove(name);
            timeLimiterRegistry.remove(name);
            circuitBreakerRegistry.remove(name);
            retryRegistry.remove(name);
            commandConfigs.remove(name);
        });
    }

    /**
     * Фиксирует одно поколение политик до закрытия ссылки.
     * Повторный close() безопасен и не уменьшает число активных ссылок повторно.
     */
    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean closed;

        private Lease(Entry entry) { this.entry = entry; }

        public CommandResilienceHolder holder() { return entry.holder; }

        /** Снимает только эту фиксацию поколения; повторный вызов ничего не меняет. */
        @Override
        public void close() {
            synchronized (CommandConfigRegistry.this) {
                if (closed) return;
                closed = true;
                entry.active--;
            }
        }
    }

    private static final class Entry {
        private final CommandProperties properties;
        private final CommandResilienceHolder holder;
        private int active;

        private Entry(CommandProperties properties, CommandResilienceHolder holder) {
            this.properties = properties;
            this.holder = holder;
        }
    }
}
