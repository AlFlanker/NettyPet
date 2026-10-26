package dev.example.integrationhost;

import jakarta.servlet.http.HttpServletRequest;

/** Pure metadata preparation on the original dispatch thread; null means no configured backend. */
@FunctionalInterface
public interface BackendRouteResolver {
    BackendRoute resolve(HttpServletRequest request, String rawPath);
}
