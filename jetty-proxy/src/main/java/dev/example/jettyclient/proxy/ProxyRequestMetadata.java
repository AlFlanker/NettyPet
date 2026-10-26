package dev.example.jettyclient.proxy;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Неизменяемый снимок исходного запроса, созданный до передачи Servlet в async.
 * target — готовый URI выбранного приложением upstream; он не изменяется header-расширением.
 * Ключи headers приведены к нижнему регистру; карта и списки значений глубоко скопированы.
 * Снимок не содержит Servlet, тела, cookies store или состояния сетевого обмена.
 */
public record ProxyRequestMetadata(String method, URI target, Map<String, List<String>> headers) {
    public ProxyRequestMetadata {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");
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

    /** Возвращает неизменяемые исходные значения без учёта регистра имени. */
    public List<String> headerValues(String name) {
        return headers.getOrDefault(Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT), List.of());
    }
}
