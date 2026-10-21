package dev.example.app.command;

import com.otr.portal.command.execution.CommandCancelledException;
import com.otr.portal.command.execution.CommandNotExecutedException;
import dev.example.proxy.transport.ProxyEngine;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;

/** The HTTP-specific rejection mapping stays outside the reusable command module. */
public final class ProxyRequestHandler {
    private final ProxyEngine engine;
    private final ProxyCommands commands;

    public ProxyRequestHandler(ProxyEngine engine, ProxyCommands commands) {
        this.engine = engine;
        this.commands = commands;
    }

    public void forward(HttpServletRequest request, HttpServletResponse response, String rawPath) throws IOException {
        var command = commands.command(engine, request, response, rawPath);
        var failure = new AtomicReference<Throwable>();
        var finished = new AtomicBoolean();
        // Intentionally subscribe on this Servlet thread. Before returning from the
        // filter the source must have entered startAsync or rejected synchronously.
        command.execute().subscribe(ignored -> { }, failure::set, () -> finished.set(true));
        Throwable rejected = unwrap(failure.get());
        // Reactor delivers non-JVM-fatal Errors such as AssertionError through onError.
        // The transport may already have started async and aborted before its start call
        // threw, so these must escape before any response access or ownership decision.
        if (rejected instanceof Error fatal) throw fatal;
        if (command.handedToProxy()) return;
        if (failure.get() == null && !finished.get()) {
            throw new IllegalStateException("Command returned without starting or rejecting");
        }
        // The transport never took ownership, so this adapter owns the rejection.
        response.setStatus(rejected == null ? 500 : rejectionStatus(rejected));
        response.setHeader("Connection", "close");
        response.setContentLength(0);
    }

    private static Throwable unwrap(Throwable failure) {
        for (int depth = 0; failure instanceof CompletionException && failure.getCause() != null && depth < 16; depth++)
            failure = failure.getCause();
        return failure;
    }

    private static int rejectionStatus(Throwable failure) {
        if (failure instanceof TimeoutException) return 504;
        if (failure instanceof BulkheadFullException || failure instanceof CallNotPermittedException
                || failure instanceof CommandNotExecutedException || failure instanceof CommandCancelledException
                || failure instanceof CancellationException) return 503;
        return 500;
    }
}
