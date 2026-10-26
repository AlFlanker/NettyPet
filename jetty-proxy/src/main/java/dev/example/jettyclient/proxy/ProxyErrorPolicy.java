package dev.example.jettyclient.proxy;

/**
 * Produces a bounded local error before commit. Runs quickly on the exchange owner,
 * with immutable metadata only; never writes Servlet response or performs external I/O.
 * A policy failure falls back to an empty 500; JVM fatal errors are propagated.
 */
@FunctionalInterface
public interface ProxyErrorPolicy {
    ProxyErrorResponse render(ProxyRequestMetadata request, int defaultStatus, Throwable failure);
}
