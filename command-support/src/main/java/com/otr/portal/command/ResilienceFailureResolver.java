package com.otr.portal.command;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.retry.MaxRetriesExceededException;
import lombok.experimental.UtilityClass;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Классифицирует исключение Resilience4j во вложенной цепочке причин.
 * Число просмотренных причин ограничено; циклическая цепочка не блокирует I/O-поток.
 */
@UtilityClass
public final class ResilienceFailureResolver {

    public static ResilienceFailureKind resolve(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; error != null && depth < 64 && seen.add(error); depth++, error = error.getCause()) {
            if (error instanceof CallNotPermittedException) return ResilienceFailureKind.CIRCUIT_OPEN;
            if (error instanceof BulkheadFullException) return ResilienceFailureKind.BULKHEAD_FULL;
            if (error instanceof TimeoutException) return ResilienceFailureKind.TIMEOUT;
            if (error instanceof MaxRetriesExceededException) return ResilienceFailureKind.RETRY_EXHAUSTED;
        }
        return ResilienceFailureKind.OTHER;
    }
}
