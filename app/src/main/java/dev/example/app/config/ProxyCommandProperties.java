package dev.example.app.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Политика приложения для логической группы proxy-команд, а не настройки транспорта. */
@ConfigurationProperties("proxy-command")
public record ProxyCommandProperties(
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("128") int maxConcurrent,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("20") int slidingWindowSize,
        @DefaultValue("10") int minimumNumberOfCalls,
        @DefaultValue("10s") Duration waitDurationInOpenState,
        @DefaultValue("3") int permittedCallsInHalfOpenState) {
    public ProxyCommandProperties {
        validateDuration(timeout, "timeout");
        validateDuration(waitDurationInOpenState, "wait-duration-in-open-state");
        if (maxConcurrent < 1 || slidingWindowSize < 1 || minimumNumberOfCalls < 1
                || minimumNumberOfCalls > slidingWindowSize || permittedCallsInHalfOpenState < 1)
            throw new IllegalArgumentException("Invalid proxy-command concurrency/window settings");
        if (!Float.isFinite(failureRateThreshold) || failureRateThreshold < 1 || failureRateThreshold > 100)
            throw new IllegalArgumentException("failure-rate-threshold must be 1..100");
    }

    private static void validateDuration(Duration value, String name) {
        if (value == null || value.isNegative() || value.compareTo(Duration.ofMillis(1)) < 0
                || value.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException(name + " must be 1ms..1d");
    }
}
