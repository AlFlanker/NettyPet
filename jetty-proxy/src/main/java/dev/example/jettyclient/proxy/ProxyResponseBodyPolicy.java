package dev.example.jettyclient.proxy;

import java.util.Objects;

/**
 * Выбирает ограниченное преобразование финального ответа; null оставляет streaming.
 * select выполняется быстро и без блокировок на владельце обмена, без Servlet,
 * тела, БД и сетевых вызовов. Engine допускает преобразование только полного
 * ответа с телом без content coding; subrange-ответы не преобразуются.
 */
@FunctionalInterface
public interface ProxyResponseBodyPolicy {
    Selection select(ProxyRequestMetadata request, ProxyResponseMetadata response);

    /** Положительные лимиты входного и итогового тела проверяет engine. */
    record Selection(long maxInputBytes, long maxOutputBytes, Rewriter rewriter) {
        public Selection {
            if (maxInputBytes < 1 || maxOutputBytes < 1)
                throw new IllegalArgumentException("Response body limits must be positive");
            Objects.requireNonNull(rewriter, "rewriter");
        }
    }

    /**
     * CPU-преобразование на ограниченном worker executor. Не читает Servlet и не
     * блокируется на внешних ресурсах. Input принадлежит engine; его буферы нельзя
     * освобождать или сохранять после возврата. Output может заимствовать input.
     */
    @FunctionalInterface
    interface Rewriter {
        ProxyResponseBody rewrite(ProxyResponseMetadata metadata, ProxyResponseBody input) throws Exception;
    }
}
