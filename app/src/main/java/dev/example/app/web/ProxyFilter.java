package dev.example.app.web;

import dev.example.app.command.ProxyRequestHandler;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

/** Маршрутизация приложения. Переиспользуемый proxy-модуль ничего не знает о префиксе. */
public final class ProxyFilter implements Filter {
    private static final String PREFIX = "/proxy";
    private final ProxyRequestHandler handler;

    public ProxyFilter(ProxyRequestHandler handler) { this.handler = Objects.requireNonNull(handler); }

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http && response instanceof HttpServletResponse outgoing) {
            String rawPath = http.getRequestURI().substring(http.getContextPath().length());
            if (rawPath.equals(PREFIX) || rawPath.startsWith(PREFIX + "/")) {
                String targetPath = rawPath.length() == PREFIX.length()
                        ? "/"
                        : rawPath.substring(PREFIX.length());
                handler.forward(http, outgoing, targetPath);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
