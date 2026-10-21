package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.model.CommandContext;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import static java.util.Objects.requireNonNull;

/**
 * Базовый класс команды с шаблоном выполнения через Resilience4j.
 * <p>
 * Объединяет: формирование контекста, декорирование {@link #doExecute()},
 * логирование ошибок и вызов {@link #fallbackOnError(Throwable)} при сбое.
 *
 * @param <T> тип результата команды
 * @author alexraspopin
 * @since 09.02.2026
 */
@Slf4j
public abstract class AbstractResilienceCommand<T> implements Command<T> {

    protected final CommandResilienceDecorator resilienceDecorator;
    protected final CommandProperties commandProperties;

    protected AbstractResilienceCommand(CommandResilienceDecorator resilienceDecorator,
                                     CommandProperties commandProperties
    ) {
        requireNonNull(commandProperties);
        requireNonNull(resilienceDecorator);
        this.resilienceDecorator = resilienceDecorator;
        this.commandProperties = commandProperties;
    }

    /**
     * Шаблон выполнения: контекст → декорирование → обработка ошибок.
     */
    @Override
    public final Mono<T> execute() {
        return Mono.defer(() -> {
            var context = CommandContext.builder()
                    .commandName(getCommandName())
                    .properties(commandProperties)
                    .build();
            return resilienceDecorator.decorate(this::doExecute, context);
        }).onErrorResume(e -> {
            logResilienceError(e, getCommandName());
            return fallbackOnError(e);
        });
    }

    /**
     * Имя команды для реестра Resilience4j и логов.
     */
    protected abstract String getCommandName();

    /**
     * Специфичная логика выполнения. Вызывается после подписки и допуска политик.
     * Источники с асинхронной очисткой внешних ресурсов используют AbstractResourceCommand.
     */
    protected abstract Mono<T> doExecute();

    /**
     * Реакция на ошибку (таймаут, CB, retry и т.д.). Возвращает fallback-результат.
     */
    protected abstract Mono<T> fallbackOnError(Throwable e);

    /**
     * Логирование ошибок Resilience4j и таймаутов в общем виде (без привязки к домену).
     *
     * @param e           исключение
     * @param commandName имя команды для контекста в логе
     */
    protected void logResilienceError(Throwable e, String commandName) {
        ResilienceFailureKind kind = ResilienceFailureResolver.resolve(e);
        Throwable cause = e.getCause() != null
                ? e.getCause()
                : e;
        switch (kind) {
            case CIRCUIT_OPEN:
                log.warn("CircuitBreaker открыт для команды '{}'. Переход к fallback.", commandName);
                break;
            case BULKHEAD_FULL:
                log.warn("Bulkhead переполнен для команды '{}'. Переход к fallback.", commandName);
                break;
            case TIMEOUT:
                log.warn("Таймаут TimeLimiter для команды '{}'. Переход к fallback.", commandName);
                break;
            case RETRY_EXHAUSTED:
                log.warn("Retry исчерпан для команды '{}'. Переход к fallback. Причина: {}", commandName, cause.getMessage());
                break;
            case OTHER:
            default:
                log.warn("Команда '{}' завершилась ошибкой: {}. Переход к fallback.", commandName, e.getMessage(), e);
                break;
        }
    }
}
