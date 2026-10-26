package dev.example.integrationhost;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;

/** Uses an application-configured authority, never an arbitrary client-provided URL. */
public final class FixedBackendRouteResolver implements BackendRouteResolver {
    private final HostProxyProperties properties;

    public FixedBackendRouteResolver(HostProxyProperties properties) { this.properties = properties; }

    @Override public BackendRoute resolve(HttpServletRequest request, String encodedPathAndQuery) {
        URI target = BackendTargets.resolve(properties.upstream(), encodedPathAndQuery);
        return new BackendRoute("main", URI.create(encodedPathAndQuery).getRawPath(), target, properties.timeout());
    }
}
