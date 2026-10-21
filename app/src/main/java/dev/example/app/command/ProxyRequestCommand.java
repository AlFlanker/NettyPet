package dev.example.app.command;

import dev.example.proxy.api.ProxyResult;
import dev.example.proxy.transport.ProxyCall;
import dev.example.proxy.transport.ProxyEngine;
import dev.example.resilience.CancellationReason;
import dev.example.resilience.Command;
import dev.example.resilience.CommandCancelledException;
import dev.example.resilience.CommandNotExecutedException;
import dev.example.resilience.CommandTask;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Команда одного входящего запроса. Только app знает обе стороны композиции.
 * Создание объекта не начинает I/O; start вызывается после допуска resilience-политик.
 */
public final class ProxyRequestCommand implements Command<ProxyResult> {
    private final ProxyEngine engine;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final String rawPath;
    private final AtomicBoolean invoked = new AtomicBoolean();
    private volatile boolean handedToProxy;

    public ProxyRequestCommand(ProxyEngine engine, HttpServletRequest request,
                               HttpServletResponse response, String rawPath) {
        this.engine = engine;
        this.request = request;
        this.response = response;
        this.rawPath = rawPath;
    }

    @Override public CommandTask<ProxyResult> start() throws IOException {
        if (!invoked.compareAndSet(false, true)) throw new IllegalStateException("Command is single-use");
        ProxyCall call = engine.forwardAsync(request, response, rawPath);
        handedToProxy = true;
        // completion прокси приходит после всего тела и cleanup, не после возврата startAsync.
        CompletionStage<ProxyResult> completion = call.completion().thenCompose(result -> switch (result.outcome()) {
            case CANCELLED -> CompletableFuture.failedFuture(new CommandCancelledException("Client/exchange cancelled"));
            case LOCAL_REJECTION -> CompletableFuture.failedFuture(new CommandNotExecutedException("Proxy rejected locally"));
            case DEADLINE_EXCEEDED -> CompletableFuture.failedFuture(new TimeoutException("Proxy exchange deadline exceeded"));
            case RESPONSE, UPSTREAM_FAILURE -> CompletableFuture.completedFuture(result);
        });
        return new CommandTask<>() {
            @Override public CompletionStage<ProxyResult> completion() { return completion; }
            @Override public void cancel(CancellationReason reason) {
                // Транспорт сериализует HTTP-завершение со своим I/O: timer thread не пишет response.
                call.cancel(reason == CancellationReason.TIMEOUT ? 504 : 503);
            }
        };
    }

    public boolean handedToProxy() { return handedToProxy; }
}
