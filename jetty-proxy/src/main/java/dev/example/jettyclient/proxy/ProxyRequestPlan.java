package dev.example.jettyclient.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

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
    private final Function<ProxyRequestMetadata, CompletionStage<ProxyHeaderPatch>> requestHeadersAsync;
    private final BiFunction<ProxyRequestMetadata, ProxyResponseMetadata, CompletionStage<ProxyHeaderPatch>> responseHeadersAsync;
    private final ProxyErrorPolicy errorPolicy;

    private ProxyRequestPlan(Builder builder) {
        target = builder.target;
        timeout = builder.timeout;
        customizers = List.copyOf(builder.customizers);
        responseBody = builder.responseBody;
        requestHeadersAsync = builder.requestHeadersAsync;
        responseHeadersAsync = builder.responseHeadersAsync;
        errorPolicy = builder.errorPolicy;
    }

    public static Builder builder(URI target) { return new Builder(target); }

    public URI target() { return target; }

    /** null означает общий exchange-timeout из конфигурации. */
    public Duration timeout() { return timeout; }

    /** Дополнительные расширения этого запроса, в порядке добавления. */
    public List<ProxyHeaderCustomizer> customizers() { return customizers; }

    /** null означает передачу тела без преобразования. */
    public ProxyResponseBodyPolicy responseBody() { return responseBody; }
    public Function<ProxyRequestMetadata, CompletionStage<ProxyHeaderPatch>> requestHeadersAsync() { return requestHeadersAsync; }
    public BiFunction<ProxyRequestMetadata, ProxyResponseMetadata, CompletionStage<ProxyHeaderPatch>> responseHeadersAsync() { return responseHeadersAsync; }
    public ProxyErrorPolicy errorPolicy() { return errorPolicy; }

    public static final class Builder {
        private final URI target;
        private Duration timeout;
        private final List<ProxyHeaderCustomizer> customizers = new ArrayList<>();
        private ProxyResponseBodyPolicy responseBody;
        private Function<ProxyRequestMetadata, CompletionStage<ProxyHeaderPatch>> requestHeadersAsync;
        private BiFunction<ProxyRequestMetadata, ProxyResponseMetadata, CompletionStage<ProxyHeaderPatch>> responseHeadersAsync;
        private ProxyErrorPolicy errorPolicy;

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

        /**
         * The function returns quickly; external work runs on the application's bounded executor.
         * Its immutable patch is applied after normal hooks, before any upstream I/O.
         * The exchange deadline also covers this stage. Late completion is ignored.
         */
        public Builder requestHeadersAsync(Function<ProxyRequestMetadata, CompletionStage<ProxyHeaderPatch>> policy) {
            requestHeadersAsync = Objects.requireNonNull(policy, "requestHeadersAsync");
            return this;
        }

        /** Like requestHeadersAsync, but gates final response headers and body demand. */
        public Builder responseHeadersAsync(BiFunction<ProxyRequestMetadata, ProxyResponseMetadata,
                CompletionStage<ProxyHeaderPatch>> policy) {
            responseHeadersAsync = Objects.requireNonNull(policy, "responseHeadersAsync");
            return this;
        }

        /** Used for failures of an admitted exchange before commit; preflight rejections remain empty. */
        public Builder errorPolicy(ProxyErrorPolicy policy) {
            errorPolicy = Objects.requireNonNull(policy, "errorPolicy");
            return this;
        }

        public ProxyRequestPlan build() { return new ProxyRequestPlan(this); }
    }
}
