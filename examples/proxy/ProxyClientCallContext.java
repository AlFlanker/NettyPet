package com.otr.portal.proxy;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntryMetadata;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Контекст исходящего проксируемого вызова
 *
 * @author alexraspopin
 * @since 15.12.2025
 */
public class ProxyClientCallContext {
    private static final Set<String> otelMdcKeys = Set.of(
            "trace_id",
            "span_id",
            "trace_flags"
    );
    private final Span span;
    private final Context ctx;
    private final AtomicBoolean ended = new AtomicBoolean(false);

    private ProxyClientCallContext(Span span, Context ctx) {
        this.span = span;
        this.ctx = ctx;
    }

    static ProxyClientCallContext start(Tracer tracer) {
        Span span = tracer.spanBuilder("proxy-out")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        Context ctx = Context.current()
                .with(span);
        return new ProxyClientCallContext(span, ctx);
    }

    <T> T inScopeWithAuth(@NotNull String userId, Supplier<T> supplier) {
        try (Scope scope = ctx.makeCurrent()) {
            Context ctxWithBaggage = Baggage.current().toBuilder()
                    .put("user.id", userId, BaggageEntryMetadata.empty())
                    .build()
                    .storeInContext(Context.current());
            try (Scope ignored = ctxWithBaggage.makeCurrent()) {
                return supplier.get();
            }
        }
    }

    public void withContext(Map<String, String> mdcOverlay, Runnable r) {
        Map<String, String> previous = MDC.getCopyOfContextMap();

        try (Scope scope = ctx.makeCurrent()) {
            applyMdcOverlay(mdcOverlay);
            r.run();
        } finally {
            restoreMdc(previous);
        }
    }

    private static void applyMdcOverlay(Map<String, String> overlay) {
        if (overlay == null || overlay.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : overlay.entrySet()) {
            String key = e.getKey();
            if (key == null || otelMdcKeys.contains(key)) {
                continue;
            }
            String value = e.getValue();
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }

    private static void restoreMdc(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }

    void close(Integer httpStatusCode) {
        if (httpStatusCode != null) {
            span.setAttribute("http.status_code", httpStatusCode);
            if (httpStatusCode >= 500) {
                span.setStatus(StatusCode.ERROR);
            }
        }
        end();
    }

    void closeError(Throwable t) {
        if (t != null) {
            span.recordException(t);
        }
        span.setStatus(StatusCode.ERROR);
        end();
    }

    void end() {
        if (ended.compareAndSet(false, true)) {
            span.end();
        }
    }
}
