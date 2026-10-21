package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.execution.CommandTask;
import com.otr.portal.command.model.CommandContext;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Cold command for I/O with asynchronous cleanup. doStart runs after admission.
 * Task completion must confirm resource cleanup even after timeout/cancellation.
 */
public abstract class AbstractResourceCommand<T> implements Command<T> {
    protected final CommandResilienceDecorator resilienceDecorator;
    protected final CommandProperties commandProperties;

    protected AbstractResourceCommand(CommandResilienceDecorator decorator, CommandProperties properties) {
        resilienceDecorator = Objects.requireNonNull(decorator, "decorator");
        commandProperties = Objects.requireNonNull(properties, "properties");
    }

    @Override public final Mono<T> execute() {
        return Mono.defer(() -> resilienceDecorator.executeTask(CommandContext.builder()
                        .commandName(getCommandName()).properties(commandProperties).build(), this::doStart))
                .onErrorResume(this::fallbackOnError);
    }

    protected abstract String getCommandName();
    protected abstract CommandTask<T> doStart() throws Exception;
    protected abstract Mono<T> fallbackOnError(Throwable failure);
}
