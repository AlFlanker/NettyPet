package dev.example.proxy.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Неизменяемые метаданные, не зависящие от Servlet или Netty. rawPath уже не содержит
 * префикс маршрутизации приложения; путь и параметры сохраняют исходное кодирование.
 * Тело запроса не входит в эти данные: им владеет транспорт.
 */
public record IncomingRequest(String method, String rawPath, String rawQuery,
                              Map<String, List<String>> headers, String remoteAddress) {
    public IncomingRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(rawPath, "rawPath");
        headers = Headers.immutableCopyOf(Objects.requireNonNull(headers, "headers"));
    }

    /** Ищет поле без учёта регистра и возвращает первое значение либо null при отсутствии. */
    public String firstHeader(String name) {
        List<String> values = headers.get(name.toLowerCase(java.util.Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
