package dev.example.app.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Политика общей логической группы команд прокси, независимая от настроек транспорта.
 *
 * @param timeout срок ожидания результата команды; таймаут запрашивает отмену, но очистка может завершиться позже
 * @param maxConcurrent предел одновременно выполняемых команд, включая освобождение ресурсов
 * @param failureRateThreshold процент ошибок, при котором CircuitBreaker открывается
 * @param slidingWindowSize число последних вызовов в окне статистики
 * @param minimumNumberOfCalls минимальное число вызовов для расчёта доли ошибок
 * @param waitDurationInOpenState срок ожидания до разрешения пробных вызовов
 * @param permittedCallsInHalfOpenState число разрешённых пробных вызовов в HALF_OPEN
 */
@ConfigurationProperties("proxy-command")
public record ProxyCommandProperties(
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("128") int maxConcurrent,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("20") int slidingWindowSize,
        @DefaultValue("10") int minimumNumberOfCalls,
        @DefaultValue("10s") Duration waitDurationInOpenState,
        @DefaultValue("3") int permittedCallsInHalfOpenState) {
    /** Отклоняет несовместимые размеры окна, лимиты, длительности и порог ошибок до запуска приложения. */
    public ProxyCommandProperties {
        validateDuration(timeout, "timeout");
        validateDuration(waitDurationInOpenState, "wait-duration-in-open-state");
        if (maxConcurrent < 1 || slidingWindowSize < 1 || minimumNumberOfCalls < 1
                || minimumNumberOfCalls > slidingWindowSize || permittedCallsInHalfOpenState < 1)
            throw new IllegalArgumentException("Invalid proxy-command concurrency/window settings");
        if (!Float.isFinite(failureRateThreshold) || failureRateThreshold < 1 || failureRateThreshold > 100)
            throw new IllegalArgumentException("failure-rate-threshold must be 1..100");
    }

    /** Ограничивает длительность диапазоном от 1 мс до суток, исключая нулевой и бесконечный бюджет. */
    private static void validateDuration(Duration value, String name) {
        if (value == null || value.isNegative() || value.compareTo(Duration.ofMillis(1)) < 0
                || value.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException(name + " must be 1ms..1d");
    }
}
