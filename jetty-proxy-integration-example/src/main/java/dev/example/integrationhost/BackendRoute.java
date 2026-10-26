package dev.example.integrationhost;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Immutable application routing decision; no Servlet, body, session or mutable backend object. */
public record BackendRoute(String backendId, String rawPath, URI target, Duration timeout) {
    public BackendRoute {
        Objects.requireNonNull(backendId);
        Objects.requireNonNull(rawPath);
        Objects.requireNonNull(target);
        Objects.requireNonNull(timeout);
        if (!rawPath.startsWith("/")) throw new IllegalArgumentException("rawPath must be origin-relative");
    }
}
