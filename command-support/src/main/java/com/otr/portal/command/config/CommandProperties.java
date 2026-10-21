package com.otr.portal.command.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import com.otr.portal.command.execution.CommandCancelledException;
import com.otr.portal.command.execution.CommandNotExecutedException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import lombok.Builder;
import lombok.Value;

/**
 * Неизменяемые настройки одной именованной команды.
 * Несколько попыток разрешены только при явном подтверждении повторяемости операции.
 */
@Value
@Builder(toBuilder = true)
public class CommandProperties {
    private static final CircuitBreakerConfig DEFAULT_CIRCUIT_BREAKER = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(5))
            .slidingWindowSize(10)
            .ignoreExceptions(CommandCancelledException.class, CommandNotExecutedException.class,
                    CancellationException.class)
            .build();

    /** Бюджет исходной операции вместе с повторами и ожиданиями; резервный путь в него не входит. */
    @Builder.Default
    Duration timeout = Duration.ofSeconds(3);
    /** Максимум операций, ещё не подтвердивших очистку, даже если их результат уже завершён по таймауту. */
    @Builder.Default
    int maxConcurrency = 50;
    /** Классифицирует общий итог команды; задаётся приложением для его результатов и исключений. */
    @Builder.Default
    CircuitBreakerConfig circuitBreakerConfig = DEFAULT_CIRCUIT_BREAKER;
    /** Общее число попыток, включая первую; значение 1 выключает повторы. */
    @Builder.Default
    int maxAttempts = 1;
    /** Подтверждение вызывающей стороны, что данные и побочные эффекты допускают повтор операции. */
    @Builder.Default
    boolean replayable = false;
    /** Пауза между допустимыми попытками; не увеличивает общий бюджет команды. */
    @Builder.Default
    Duration retryWaitDuration = Duration.ofMillis(500);

    /**
     * Проверяет общий бюджет, параллелизм и условия повторения до создания политик.
     * Несколько попыток требуют replayable=true: пользователь подтверждает возможность повторить действие.
     */
    public CommandProperties(Duration timeout, int maxConcurrency, CircuitBreakerConfig circuitBreakerConfig,
                             int maxAttempts, boolean replayable, Duration retryWaitDuration) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        // Общий бюджет измеряется в наносекундах: заранее исключаем переполнение при его расчёте.
        try {
            if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() < 1) {
                throw new IllegalArgumentException("timeout must be at least one millisecond");
            }
            timeout.toNanos();
        } catch (ArithmeticException excessive) {
            throw new IllegalArgumentException("timeout is too large", excessive);
        }
        if (maxConcurrency < 1) throw new IllegalArgumentException("maxConcurrency must be positive");
        this.maxConcurrency = maxConcurrency;
        this.circuitBreakerConfig = Objects.requireNonNull(circuitBreakerConfig, "circuitBreakerConfig");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (maxAttempts > 1 && !replayable) {
            throw new IllegalArgumentException("Retry requires replayable=true");
        }
        this.maxAttempts = maxAttempts;
        this.replayable = replayable;
        this.retryWaitDuration = Objects.requireNonNull(retryWaitDuration, "retryWaitDuration");
        if (retryWaitDuration.isNegative()) throw new IllegalArgumentException("retryWaitDuration must not be negative");
        try {
            retryWaitDuration.toNanos();
        } catch (ArithmeticException excessive) {
            throw new IllegalArgumentException("retryWaitDuration is too large", excessive);
        }
    }

    public static CommandProperties defaultProperties() {
        return builder().build();
    }
}
