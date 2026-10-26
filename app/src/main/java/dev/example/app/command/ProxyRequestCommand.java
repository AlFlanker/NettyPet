package dev.example.app.command;

import com.otr.portal.command.AbstractResourceCommand;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.execution.CancellationReason;
import com.otr.portal.command.execution.CommandCancelledException;
import com.otr.portal.command.execution.CommandNotExecutedException;
import com.otr.portal.command.execution.CommandTask;
import dev.example.proxy.api.ProxyResult;
import dev.example.proxy.transport.ProxyCall;
import dev.example.proxy.transport.ProxyEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;

/**
 * Представляет один входящий запрос как одноразовую ресурсную команду. Подписка запускает
 * I/O только после допуска лимитерами; completion задачи подтверждает очистку всего обмена.
 */
public final class ProxyRequestCommand extends AbstractResourceCommand<ProxyResult> {
    private final ProxyEngine engine;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final String rawPath;
    private final AtomicBoolean invoked = new AtomicBoolean();
    private volatile boolean handedToProxy;

    public ProxyRequestCommand(CommandResilienceDecorator decorator, CommandProperties properties,
                               ProxyEngine engine, HttpServletRequest request,
                               HttpServletResponse response, String rawPath) {
        super(decorator, properties);
        this.engine = engine;
        this.request = request;
        this.response = response;
        this.rawPath = rawPath;
    }

    @Override protected String getCommandName() { return ProxyCommands.NAME; }

    /** Передаёт ошибку HTTP-обработчику без записи резервного тела в Servlet-ответ. */
    @Override protected Mono<ProxyResult> fallbackOnError(Throwable failure) {
        // До передачи запроса транспорту отказ преобразует HTTP-обработчик. После передачи
        // только транспорт завершает или прерывает ответ; подменять его тело здесь нельзя.
        return Mono.error(failure);
    }

    /**
     * Однократно передаёт запрос транспорту после допуска команды. Сигнал completion
     * появляется после освобождения ресурсов; локальные исходы превращаются в исключения,
     * чтобы политика CircuitBreaker могла отличить их от отказов целевого сервера.
     */
    @Override
    protected CommandTask<ProxyResult> doStart() throws IOException {
        if (!invoked.compareAndSet(false, true)) {
            throw new IllegalStateException("Command is single-use");
        }
        ProxyCall call = engine.forwardAsync(request, response, rawPath);
        handedToProxy = true;
        // HTTP-ошибки целевого сервера остаются ProxyResult для проверки recordResult.
        // Исключения обозначают локальный отказ, отмену или общий срок обмена.
        CompletionStage<ProxyResult> completion = call.completion().thenCompose(result -> switch (result.outcome()) {
            case CANCELLED -> CompletableFuture.failedFuture(new CommandCancelledException("Client/exchange cancelled"));
            case LOCAL_REJECTION -> CompletableFuture.failedFuture(new CommandNotExecutedException("Proxy rejected locally"));
            case DEADLINE_EXCEEDED -> CompletableFuture.failedFuture(new TimeoutException("Proxy exchange deadline exceeded"));
            case RESPONSE, UPSTREAM_FAILURE -> CompletableFuture.completedFuture(result);
        });
        return new CommandTask<>() {
            @Override public CompletionStage<ProxyResult> completion() { return completion; }
            @Override public void cancel(CancellationReason reason) {
                // Транспорт выполняет отмену на потоке обмена; поток таймера не обращается к HTTP-ответу.
                call.cancel(reason == CancellationReason.TIMEOUT ? 504 : 503);
            }
        };
    }

    /** Показывает, что forwardAsync() вернулся и HTTP-обработчик больше не должен писать ответ. */
    public boolean handedToProxy() {
        return handedToProxy;
    }
}
