package dev.example.integrationhost;

import dev.example.proxy.transport.ProxyEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Delegates to the existing Netty library, which owns its own async lifecycle. */
public final class LegacyProxyHandler implements ProxyRequestHandler {
    private final ProxyEngine engine;

    public LegacyProxyHandler(ProxyEngine engine) { this.engine = engine; }

    @Override public void handle(HttpServletRequest request, HttpServletResponse response, BackendRoute route)
            throws IOException {
        engine.forwardAsync(request, response, route.rawPath());
    }
}
