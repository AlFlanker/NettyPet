package dev.example.proxy.core;

import dev.example.proxy.api.Headers;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Применяет правила HTTP-заголовков независимо от прикладной политики. Служебные поля
 * одного соединения не передаются на следующее соединение (RFC 9110, раздел 7.6.1).
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

    /** Копирует поля запроса, удаляя заголовки соединения, Host, Content-Length и Expect. */
    public static Headers request(Map<String, List<String>> source) {
        return sanitize(Headers.copyOf(source)).remove("host").remove("content-length").remove("expect");
    }

    /** Подготавливает независимую копию полей запроса, сохраняя исходный набор без изменений. */
    public static Headers request(Headers source) {
        return sanitize(source.copy()).remove("host").remove("content-length").remove("expect");
    }

    /** Копирует поля ответа, исключая поля, действующие только на исходном соединении. */
    public static Headers response(Map<String, List<String>> source) {
        return sanitize(Headers.copyOf(source));
    }

    /** Очищает копию полей ответа; переданный набор остаётся доступен его владельцу. */
    public static Headers response(Headers source) { return sanitize(source.copy()); }

    /**
     * Создаёт неизменяемый снимок полей после выполнения процессоров запроса. Служебные
     * поля и имена из Connection исключаются без промежуточной изменяемой копии.
     * Изменение исходного плана после вызова не меняет списки в возвращённом снимке.
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
     * Удаляет поля одного соединения из переданного набора. Имена из Connection
     * обрабатываются до удаления самого Connection, чтобы не потерять список исключений.
     * Дополнительные ограничения запроса применяют request и requestSnapshot.
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
                // Правила списков HTTP допускают пустые элементы. Непустой элемент должен
                // быть допустимым именем поля; Headers.remove выполняет эту проверку.
                if (!name.isEmpty()) headers.remove(name);
                if (comma < 0) break;
                start = comma + 1;
            }
        }
        HOP_BY_HOP.forEach(headers::remove);
        return headers;
    }
}
