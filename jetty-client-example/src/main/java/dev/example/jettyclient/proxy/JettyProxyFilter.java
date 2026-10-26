package dev.example.jettyclient.proxy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Прикладной Filter-адаптер; не владеет lifecycle общего транспорта.
 * Регистрируется с asyncSupported=true. Async/error dispatch не запускает новый обмен.
 */
public final class JettyProxyFilter extends OncePerRequestFilter {
    private final ProxyExchangeExecutor executor;
    private final ProxyRequestPlanner planner;

    public JettyProxyFilter(ProxyExchangeExecutor executor, ProxyRequestPlanner planner) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        ProxyRequestPlan plan = planner.prepare(request);
        if (plan == null) {
            chain.doFilter(request, response);
            return;
        }
        executor.forward(request, response, null, plan);
    }
}
