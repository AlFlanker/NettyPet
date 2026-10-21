package dev.example.proxy.core;

import dev.example.proxy.api.Headers;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Только правила HTTP, без бизнес-политик. Hop-by-hop поля относятся к одному TCP hop
 * и не должны переноситься на другое соединение (RFC 9110, раздел 7.6.1).
 */
public final class HeaderRules {
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-connection", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");
    private static final Set<String> REQUEST_EXCLUDED;

    static {
        var excluded = new HashSet<>(HOP_BY_HOP);
        excluded.addAll(Set.of("host", "content-length", "expect"));
        REQUEST_EXCLUDED = Set.copyOf(excluded);
    }

    private HeaderRules() {}

    public static Headers request(Map<String, List<String>> source) {
        return sanitize(Headers.copyOf(source)).remove("host").remove("content-length").remove("expect");
    }

    public static Headers request(Headers source) {
        return sanitize(source.copy()).remove("host").remove("content-length").remove("expect");
    }

    public static Headers response(Map<String, List<String>> source) {
        return sanitize(Headers.copyOf(source));
    }

    public static Headers response(Headers source) { return sanitize(source.copy()); }

    /**
     * Финальный снимок после Processor без промежуточной изменяемой копии заголовков.
     * Исходный план сохраняется, а списки snapshot не разделяют mutable-состояние с ним.
     */
    public static Map<String, List<String>> requestSnapshot(Headers source) {
        Set<String> excluded = REQUEST_EXCLUDED;
        List<String> connections = source.get("connection");
        if (!connections.isEmpty()) {
            var dynamic = new HashSet<>(REQUEST_EXCLUDED);
            for (String value : connections) {
                int start = 0;
                while (start <= value.length()) {
                    int comma = value.indexOf(',', start);
                    int end = comma < 0 ? value.length() : comma;
                    String name = value.substring(start, end).trim();
                    if (!name.isEmpty()) dynamic.add(Headers.canonicalName(name));
                    if (comma < 0) break;
                    start = comma + 1;
                }
            }
            excluded = dynamic;
        }
        Set<String> omitted = excluded;
        return source.asMap(name -> !omitted.contains(name));
    }

    /**
     * Изменяет переданный экземпляр. Вызывается до и после Processor: расширение не
     * может вернуть transport-заголовки в исходящую сторону. Host/CL/TE задаёт транспорт.
     */
    public static Headers sanitize(Headers headers) {
        // Снимок всех строк обязателен: одна из опций может называться Connection.
        for (String value : headers.get("connection")) {
            // Без split(): длинный список не создаёт дополнительный массив всех токенов.
            int start = 0;
            while (start <= value.length()) {
                int comma = value.indexOf(',', start);
                int end = comma < 0 ? value.length() : comma;
                String name = value.substring(start, end).trim();
                // RFC list grammar допускает пустые элементы. Непустой элемент обязан
                // быть token; Headers.remove проверяет его так же строго, как имя поля.
                if (!name.isEmpty()) headers.remove(name);
                if (comma < 0) break;
                start = comma + 1;
            }
        }
        HOP_BY_HOP.forEach(headers::remove);
        return headers;
    }
}
