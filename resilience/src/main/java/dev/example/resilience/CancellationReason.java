package dev.example.resilience;

/** Причина кооперативной остановки операции. */
public enum CancellationReason {
    TIMEOUT, CALLER, SHUTDOWN
}
