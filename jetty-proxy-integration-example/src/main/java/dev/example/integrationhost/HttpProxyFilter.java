package dev.example.integrationhost;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** One application entry point; only the selected handler may take body/async ownership. */
public final class HttpProxyFilter implements Filter {
    private static final String PREFIX = "/backend";
    private final BackendRouteResolver routes;
    private final BooleanSupplier maintenance;
    private final Predicate<BackendRoute> forbidden;
    private final ProxyRequestHandler handler;

    public HttpProxyFilter(BackendRouteResolver routes, BooleanSupplier maintenance,
            Predicate<BackendRoute> forbidden, ProxyRequestHandler handler) {
        this.routes = Objects.requireNonNull(routes);
        this.maintenance = Objects.requireNonNull(maintenance);
        this.forbidden = Objects.requireNonNull(forbidden);
        this.handler = Objects.requireNonNull(handler);
    }

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest incoming) || !(response instanceof HttpServletResponse outgoing)) {
            chain.doFilter(request, response);
            return;
        }
        String path = incoming.getRequestURI().substring(incoming.getContextPath().length());
        if (!(path.equals(PREFIX) || path.startsWith(PREFIX + "/"))) {
            chain.doFilter(request, response);
            return;
        }
        if (maintenance.getAsBoolean()) { reject(outgoing, 503); return; }
        String rawPath = path.length() == PREFIX.length() ? "/" : path.substring(PREFIX.length());
        BackendRoute route = routes.resolve(incoming, rawPath);
        if (route == null) { reject(outgoing, 404); return; }
        if (forbidden.test(route)) { reject(outgoing, 403); return; }
        // Startup wiring chooses this handler. A future selector belongs here,
        // before this call, and must keep its decision for the entire request.
        handler.handle(incoming, outgoing, route);
    }

    private static void reject(HttpServletResponse response, int code) {
        response.setStatus(code);
        response.setContentLength(0);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Connection", "close");
    }
}
