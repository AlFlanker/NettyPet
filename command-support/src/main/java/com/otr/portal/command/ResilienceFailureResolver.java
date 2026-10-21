package com.otr.portal.command;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.retry.MaxRetriesExceededException;
import lombok.experimental.UtilityClass;

import java.util.concurrent.TimeoutException;

/**
 * Классифицирует исключение Resilience4j, включая обёртку в {@link Throwable#getCause()}.
 */
@UtilityClass
public final class ResilienceFailureResolver {

    public static ResilienceFailureKind resolve(Throwable error) {
        if (error == null) {
            return ResilienceFailureKind.OTHER;
        }
        Throwable cause = error.getCause() != null
                ? error.getCause()
                : error;
        if (isKind(error, cause, CallNotPermittedException.class)) {
            return ResilienceFailureKind.CIRCUIT_OPEN;
        }
        if (isKind(error, cause, BulkheadFullException.class)) {
            return ResilienceFailureKind.BULKHEAD_FULL;
        }
        if (isKind(error, cause, TimeoutException.class)) {
            return ResilienceFailureKind.TIMEOUT;
        }
        if (isKind(error, cause, MaxRetriesExceededException.class)) {
            return ResilienceFailureKind.RETRY_EXHAUSTED;
        }
        return ResilienceFailureKind.OTHER;
    }

    private static boolean isKind(Throwable error, Throwable cause, Class<? extends Throwable> type) {
        return type.isInstance(error) || type.isInstance(cause);
    }
}
