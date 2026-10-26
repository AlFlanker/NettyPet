package dev.example.integrationhost;

import dev.example.proxy.transport.ProxyEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Delegates to the existing Netty library, which owns its own async lifecycle. */
public final class LegacyProxyHandler implements ProxyRequestHandler {
    private final ProxyEngine engine;
    private final HostProxyProperties fixedBackend;

    public LegacyProxyHandler(ProxyEngine engine, HostProxyProperties fixedBackend) {
        this.engine = engine;
        this.fixedBackend = fixedBackend;
    }

    @Override public void handle(HttpServletRequest request, HttpServletResponse response, BackendRoute route)
            throws IOException {
        String query = request.getQueryString();
        var supportedTarget = BackendTargets.resolve(fixedBackend.upstream(),
                route.rawPath() + (query == null ? "" : "?" + query));
        // This adapter's Netty engine is configured once for one backend and timeout.
        // Reject unsupported dynamic decisions before it can read a body or start async.
        if (!supportedTarget.equals(route.target()) || !fixedBackend.timeout().equals(route.timeout())) {
            response.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
            response.setContentLength(0);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Proxy-Capability", "fixed-backend-and-timeout-only");
            response.setHeader("Connection", "close");
            return;
        }
        engine.forwardAsync(request, response, route.rawPath());
    }
}
