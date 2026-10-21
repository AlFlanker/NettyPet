package com.otr.portal.command;

/**
 * Причина срабатывания Resilience4j-fallback.
 */
public enum ResilienceFailureKind {
    TIMEOUT,
    CIRCUIT_OPEN,
    BULKHEAD_FULL,
    RETRY_EXHAUSTED,
    OTHER
}
