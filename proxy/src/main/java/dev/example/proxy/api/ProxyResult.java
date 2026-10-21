package dev.example.proxy.api;

import java.util.Objects;

/**
 * Итог транспортного обмена после освобождения его ресурсов.
 * status — логический статус результата; 0 означает отмену до получения или формирования ответа.
 * После отправки заголовков последующая ошибка не меняет уже переданный клиенту статус.
 */
public record ProxyResult(int status, Outcome outcome) {
    public ProxyResult {
        if (status != 0 && (status < 100 || status > 599))
            throw new IllegalArgumentException("Status must be 0 or 100..599");
        Objects.requireNonNull(outcome, "outcome");
    }

    /** Причина завершения транспорта; политику повторов и CircuitBreaker выбирает приложение. */
    public enum Outcome {
        /** Ответ сервера передан и ресурсы обмена освобождены; HTTP-статус может быть ошибочным. */
        RESPONSE,
        /** Обмен завершён ошибкой соединения, протокола или ожидания ответа целевого сервера. */
        UPSTREAM_FAILURE,
        /** Обмен отклонён локальной проверкой, ограничением ресурсов или остановкой прокси. */
        LOCAL_REJECTION,
        /** Получен сигнал отмены; завершение результата означает, что очистка уже закончилась. */
        CANCELLED,
        /** Истёк общий транспортный бюджет обмена, а не отдельное ожидание заголовков. */
        DEADLINE_EXCEEDED
    }
}
