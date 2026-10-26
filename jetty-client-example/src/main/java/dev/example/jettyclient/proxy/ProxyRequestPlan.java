package dev.example.jettyclient.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Неизменяемые настройки одного запроса, подготовленные до передачи в async.
 * Не содержит Servlet и не читает тело. Расширения должны захватывать только
 * подготовленный контекст и соблюдать собственные контракты потокобезопасности.
 */
public final class ProxyRequestPlan {
    private final URI target;
    private final Duration timeout;
    private final List<ProxyHeaderCustomizer> customizers;
    private final ProxyResponseBodyPolicy responseBody;

    private ProxyRequestPlan(Builder builder) {
        target = builder.target;
        timeout = builder.timeout;
        customizers = List.copyOf(builder.customizers);
        responseBody = builder.responseBody;
    }

    public static Builder builder(URI target) { return new Builder(target); }

    public URI target() { return target; }

    /** null означает общий exchange-timeout из конфигурации. */
    public Duration timeout() { return timeout; }

    /** Дополнительные расширения этого запроса, в порядке добавления. */
    public List<ProxyHeaderCustomizer> customizers() { return customizers; }

    /** null означает передачу тела без преобразования. */
    public ProxyResponseBodyPolicy responseBody() { return responseBody; }

    public static final class Builder {
        private final URI target;
        private Duration timeout;
        private final List<ProxyHeaderCustomizer> customizers = new ArrayList<>();
        private ProxyResponseBodyPolicy responseBody;

        private Builder(URI target) { this.target = Objects.requireNonNull(target, "target"); }

        /** Таймаут должен представляться в наносекундах long и быть не меньше 1 мс. */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            try {
                if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() < 1)
                    throw new IllegalArgumentException("timeout must be at least 1ms");
                timeout.toNanos();
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("timeout is too large", overflow);
            }
            this.timeout = timeout;
            return this;
        }

        public Builder customizer(ProxyHeaderCustomizer customizer) {
            customizers.add(Objects.requireNonNull(customizer, "customizer"));
            return this;
        }

        public Builder responseBody(ProxyResponseBodyPolicy responseBody) {
            this.responseBody = Objects.requireNonNull(responseBody, "responseBody");
            return this;
        }

        public ProxyRequestPlan build() { return new ProxyRequestPlan(this); }
    }
}
