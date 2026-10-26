package dev.example.integrationhost;

import jakarta.servlet.http.HttpServletRequest;

/** Uses an application-configured authority, never an arbitrary client-provided URL. */
public final class FixedBackendRouteResolver implements BackendRouteResolver {
    private final HostProxyProperties properties;

    public FixedBackendRouteResolver(HostProxyProperties properties) { this.properties = properties; }

    @Override public BackendRoute resolve(HttpServletRequest request, String rawPath) {
        String query = request.getQueryString();
        return new BackendRoute("main", rawPath, BackendTargets.resolve(properties.upstream(),
                rawPath + (query == null ? "" : "?" + query)), properties.timeout());
    }
}
