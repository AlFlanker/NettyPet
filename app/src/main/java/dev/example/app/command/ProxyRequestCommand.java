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
 * One incoming request is a single-use resource command. Subscription starts I/O only
 * after admission; the task's completion confirms that the entire exchange is cleaned up.
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

    @Override protected Mono<ProxyResult> fallbackOnError(Throwable failure) {
        // Before handoff the HTTP adapter maps this failure. After handoff only the
        // transport may complete or abort the response; no replacement body is written.
        return Mono.error(failure);
    }

    @Override protected CommandTask<ProxyResult> doStart() throws IOException {
        if (!invoked.compareAndSet(false, true)) throw new IllegalStateException("Command is single-use");
        ProxyCall call = engine.forwardAsync(request, response, rawPath);
        handedToProxy = true;
        CompletionStage<ProxyResult> completion = call.completion().thenCompose(result -> switch (result.outcome()) {
            case CANCELLED -> CompletableFuture.failedFuture(new CommandCancelledException("Client/exchange cancelled"));
            case LOCAL_REJECTION -> CompletableFuture.failedFuture(new CommandNotExecutedException("Proxy rejected locally"));
            case DEADLINE_EXCEEDED -> CompletableFuture.failedFuture(new TimeoutException("Proxy exchange deadline exceeded"));
            case RESPONSE, UPSTREAM_FAILURE -> CompletableFuture.completedFuture(result);
        });
        return new CommandTask<>() {
            @Override public CompletionStage<ProxyResult> completion() { return completion; }
            @Override public void cancel(CancellationReason reason) {
                // Transport serializes completion with its I/O; the timer thread never writes HTTP.
                call.cancel(reason == CancellationReason.TIMEOUT ? 504 : 503);
            }
        };
    }

    public boolean handedToProxy() { return handedToProxy; }
}
