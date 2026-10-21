package com.otr.portal.command;

/**
 * Вид сбоя команды для понятного сообщения при резервной обработке.
 */
public enum ResilienceFailureKind {
    TIMEOUT,
    CIRCUIT_OPEN,
    BULKHEAD_FULL,
    RETRY_EXHAUSTED,
    OTHER
}
