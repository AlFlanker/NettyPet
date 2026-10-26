package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.execution.CommandTask;
import com.otr.portal.command.model.CommandContext;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Холодная команда для операций с асинхронной очисткой внешних ресурсов.
 * Метод doStart() вызывается после допуска лимитерами. Завершение возвращённой задачи
 * должно подтверждать очистку и после ошибки, и после таймаута либо отмены.
 */
public abstract class AbstractResourceCommand<T> implements Command<T> {
    protected final CommandResilienceDecorator resilienceDecorator;
    protected final CommandProperties commandProperties;

    protected AbstractResourceCommand(CommandResilienceDecorator decorator, CommandProperties properties) {
        resilienceDecorator = Objects.requireNonNull(decorator, "decorator");
        commandProperties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * При подписке запускает защищённую ресурсную операцию, а её ошибку передаёт в резервный путь.
     * Таймаут результата не завершает очистку задачи и не возвращает её слот раньше времени.
     */
    @Override
    public final Mono<T> execute() {
        return Mono.defer(() -> resilienceDecorator.executeTask(CommandContext.builder()
                        .commandName(getCommandName()).properties(commandProperties).build(), this::doStart))
                .onErrorResume(this::fallbackOnError);
    }

    /** Возвращает стабильное имя логической операции, по которому используются общие политики. */
    protected abstract String getCommandName();
    /**
     * Коротко и без блокировки запускает очередную попытку после допуска лимитерами.
     * Возвращённая задача подтверждает полную очистку через completion(); если запуск бросает
     * исключение до возврата задачи, реализация обязана сама убрать уже созданные ресурсы.
     */
    protected abstract CommandTask<T> doStart() throws Exception;
    /**
     * Создаёт резервный результат вне политик исходной операции.
     * Вызывается и при таймауте, когда исходная задача ещё может очищать ресурсы и занимать слот.
     */
    protected abstract Mono<T> fallbackOnError(Throwable failure);
}
