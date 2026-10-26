package dev.example.integrationhost;

import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.ProxyRequestPlan;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** The new branch owns its async and does not implement the legacy executor contract. */
public final class JettyProxyHandler implements ProxyRequestHandler {
    private final JettyProxyEngine engine;

    public JettyProxyHandler(JettyProxyEngine engine) { this.engine = engine; }

    @Override public void handle(HttpServletRequest request, HttpServletResponse response, BackendRoute route) {
        engine.forward(request, response, ProxyRequestPlan.builder(route.target()).timeout(route.timeout()).build());
    }
}
