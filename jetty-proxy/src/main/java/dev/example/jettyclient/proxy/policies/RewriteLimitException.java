package dev.example.jettyclient.proxy.policies;

import dev.example.jettyclient.proxy.ProxyBodyLimitException;

/** A selected application rewrite exceeded its byte or component budget before allocating the next output span. */
public final class RewriteLimitException extends ProxyBodyLimitException {
    public RewriteLimitException(String message) { super(message); }
}
