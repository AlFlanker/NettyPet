package dev.example.proxy.api;

import java.util.Objects;

/**
 * Итог транспортного обмена после освобождения его ресурсов.
 * status — логический статус результата; 0 означает отмену до получения/формирования ответа.
 * Если ответ уже committed, последующая ошибка не меняет ранее отправленный клиенту статус.
 */
public record ProxyResult(int status, Outcome outcome) {
    public ProxyResult {
        if (status != 0 && (status < 100 || status > 599))
            throw new IllegalArgumentException("Status must be 0 or 100..599");
        Objects.requireNonNull(outcome, "outcome");
    }

    /** Причина завершения транспорта; решение о retry/circuit breaker принимает приложение. */
    public enum Outcome {
        RESPONSE,
        UPSTREAM_FAILURE,
        LOCAL_REJECTION,
        CANCELLED,
        DEADLINE_EXCEEDED
    }
}
