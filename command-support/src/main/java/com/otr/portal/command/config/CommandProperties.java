package com.otr.portal.command.config;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;

/**
 * Настройки Resilience4j для команды (timeout, maxConcurrency).
 *
 * @author alexraspopin
 * @since 06.02.2026
 */
@Value
@Builder
public class CommandProperties {
    @Builder.Default
    Duration timeout = Duration.ofSeconds(3);
    @Builder.Default
    int maxConcurrency = 50;

    public static CommandProperties defaultProperties() {
        return builder().build();
    }
}
