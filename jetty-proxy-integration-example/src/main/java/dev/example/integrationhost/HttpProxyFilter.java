package dev.example.integrationhost;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** One application entry point; only the selected handler may take body/async ownership. */
public final class HttpProxyFilter implements Filter {
    private static final String PREFIX = "/backend";
    static final String RESOURCE_ROUTE_ATTRIBUTE = HttpProxyFilter.class.getName() + ".resourceRoute";
    private final ApplicationRoutePolicy policy;
    private final ProxyRequestHandler handler;

    public HttpProxyFilter(BackendRouteResolver routes, BooleanSupplier maintenance,
            Predicate<BackendRoute> forbidden, ProxyRequestHandler handler) {
        this(new ApplicationRoutePolicy(routes, maintenance, forbidden), handler);
    }

    public HttpProxyFilter(ApplicationRoutePolicy policy, ProxyRequestHandler handler) {
        this.policy = Objects.requireNonNull(policy);
        this.handler = Objects.requireNonNull(handler);
    }

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest incoming) || !(response instanceof HttpServletResponse outgoing)) {
            chain.doFilter(request, response);
            return;
        }
        String path = incoming.getRequestURI().substring(incoming.getContextPath().length());
        boolean resource = path.equals("/resource");
        if (!resource && !(path.equals(PREFIX) || path.startsWith(PREFIX + "/"))) {
            chain.doFilter(request, response);
            return;
        }
        BackendRoute route;
        try {
            String encodedPathAndQuery;
            if (resource) encodedPathAndQuery = resourcePath(incoming.getQueryString());
            else {
                String rawPath = path.length() == PREFIX.length() ? "/" : path.substring(PREFIX.length());
                String query = incoming.getQueryString();
                encodedPathAndQuery = rawPath + (query == null ? "" : "?" + query);
            }
            route = policy.authorize(incoming, encodedPathAndQuery);
        }
        catch (ApplicationRoutePolicy.RejectedRoute rejected) { reject(outgoing, rejected.status()); return; }
        catch (IllegalArgumentException invalid) { reject(outgoing, 400); return; }
        if (resource) {
            incoming.setAttribute(RESOURCE_ROUTE_ATTRIBUTE, route);
            chain.doFilter(request, response);
            return;
        }
        // Startup wiring chooses this handler. A future selector belongs here,
        // before this call, and must keep its decision for the entire request.
        handler.handle(incoming, outgoing, route);
    }

    /** Query-only include path: do not invoke Servlet parameter/form parsing on a POST body. */
    private static String resourcePath(String query) {
        String path = null;
        if (query != null) for (String parameter : query.split("&", -1)) {
            int equals = parameter.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? parameter : parameter.substring(0, equals),
                    StandardCharsets.UTF_8);
            if (!name.equals("path")) continue;
            if (path != null) throw new IllegalArgumentException("Resource path must occur once in query");
            path = URLDecoder.decode(equals < 0 ? "" : parameter.substring(equals + 1), StandardCharsets.UTF_8);
        }
        if (path == null) throw new IllegalArgumentException("Resource path is required in query");
        return path;
    }

    private static void reject(HttpServletResponse response, int code) {
        response.setStatus(code);
        response.setContentLength(0);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Connection", "close");
    }
}
