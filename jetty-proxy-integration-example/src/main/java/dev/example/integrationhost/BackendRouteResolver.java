package dev.example.integrationhost;

import jakarta.servlet.http.HttpServletRequest;

/** Pure metadata preparation; input is encoded origin-relative path/query, null means no backend. */
@FunctionalInterface
public interface BackendRouteResolver {
    BackendRoute resolve(HttpServletRequest request, String encodedPathAndQuery);
}
