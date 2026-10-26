package dev.example.jettyclient.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Неизменяемый снимок финальных upstream headers до преобразования ответа. */
public record ProxyResponseMetadata(int status, Map<String, List<String>> headers) {
    public ProxyResponseMetadata {
        Objects.requireNonNull(headers, "headers");
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        headers.forEach((name, values) -> snapshot.merge(
                Objects.requireNonNull(name, "header name").toLowerCase(Locale.ROOT), List.copyOf(values),
                (first, second) -> {
                    List<String> combined = new ArrayList<>(first.size() + second.size());
                    combined.addAll(first);
                    combined.addAll(second);
                    return List.copyOf(combined);
                }));
        headers = Collections.unmodifiableMap(snapshot);
    }

    /** Возвращает исходные значения без учёта регистра имени; Set-Cookie не склеивается. */
    public List<String> headerValues(String name) {
        return headers.getOrDefault(Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT), List.of());
    }
}
