package dev.example.app.command;

import dev.example.proxy.api.ProxyResult;
import dev.example.proxy.transport.ProxyEngine;
import dev.example.resilience.CommandCancelledException;
import dev.example.resilience.CommandExecutor;
import dev.example.resilience.CommandNotExecutedException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/** HTTP-адаптер отказов команды; reusable resilience-модуль не знает коды HTTP. */
public final class ProxyRequestHandler {
    private final ProxyEngine engine;
    private final CommandExecutor<ProxyResult> commands;

    public ProxyRequestHandler(ProxyEngine engine, CommandExecutor<ProxyResult> commands) {
        this.engine = engine;
        this.commands = commands;
    }

    public void forward(HttpServletRequest request, HttpServletResponse response, String rawPath) throws IOException {
        var command = new ProxyRequestCommand(engine, request, response, rawPath);
        var execution = commands.execute(command);
        if (command.handedToProxy()) {
            return;
        }
        // execute() либо синхронно передал владение proxy, либо уже завершил отказ.
        // После передачи HTTP-ответом владеет только proxy; observer команды его не переписывает.
        var result = execution.result().toCompletableFuture();
        if (!result.isDone()) {
            throw new IllegalStateException("Command returned without starting or rejecting");
        }
        int status = 500;
        try {
            result.getNow(null);
        } catch (CompletionException | CancellationException failure) {
            status = rejectionStatus(failure);
        }
        response.setStatus(status);
        response.setHeader("Connection", "close");
        response.setContentLength(0);
    }

    private static int rejectionStatus(Throwable failure) {
        for (int depth = 0; failure instanceof CompletionException && failure.getCause() != null && depth < 16; depth++)
            failure = failure.getCause();
        if (failure instanceof TimeoutException) {
            return 504;
        }
        if (failure instanceof BulkheadFullException || failure instanceof CallNotPermittedException
                || failure instanceof CommandNotExecutedException || failure instanceof CommandCancelledException
                || failure instanceof CancellationException) {
            return 503;
        }

        return 500;
    }
}
