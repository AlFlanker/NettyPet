package dev.example.proxy.transport;

import dev.example.proxy.api.ProxyRequest;
import dev.example.proxy.api.ProxyRejection;
import dev.example.proxy.core.HeaderRules;
import dev.example.proxy.core.UpstreamUri;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Неизменяемый снимок после обработки расширениями: пул соединений, Host и путь запроса
 * формируются по одному URI. Сохранённая расширением ссылка на ProxyRequest больше
 * не позволяет менять подготовленный сетевой запрос.
 */
record PreparedRequest(String method, URI uri, Map<String, List<String>> headers,
                       long contentLength, boolean declaredLength) {
    /** Проверяет итоговый URI и заголовки, фиксирует длину тела и исключает авторизацию, привязанную к соединению. */
    static PreparedRequest capture(HttpServletRequest incoming, ProxyRequest mutable) {
        URI uri = UpstreamUri.validate(mutable.uri());
        var headers = HeaderRules.requestSnapshot(mutable.headers());
        // Пул используется разными клиентами. NTLM/Negotiate могут привязать учётную запись
        // к TCP-соединению и передать её другому клиенту при повторном использовании канала.
        // Проверяем итоговые заголовки: расширение могло добавить или заменить авторизацию.
        for (String authorization : headers.getOrDefault("authorization", List.of())) {
            if (connectionBoundAuthentication(authorization))
                throw new ProxyRejection(501, "Connection-bound authentication is unsupported");
        }
        long declared = incoming.getContentLengthLong();
        long length = declared;
        // В HTTP/1 отсутствие Content-Length и Transfer-Encoding означает отсутствие тела.
        // В HTTP/2 длина может быть неизвестна до END_STREAM; целевому серверу
        // такое тело передаётся с кодированием chunked.
        if (length < 0 && incoming.getProtocol().startsWith("HTTP/1.")
                && incoming.getHeader("Transfer-Encoding") == null) length = 0;
        return new PreparedRequest(incoming.getMethod(), uri,
                headers, length, declared >= 0);
    }

    /** Распознаёт NTLM/Negotiate по имени схемы, не анализируя и не сохраняя сам токен авторизации. */
    private static boolean connectionBoundAuthentication(String value) {
        int start = 0;
        while (start < value.length() && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) start++;
        int end = start;
        while (end < value.length() && value.charAt(end) != ' ' && value.charAt(end) != '\t') end++;
        int length = end - start;
        return length == 4 && value.regionMatches(true, start, "NTLM", 0, 4)
                || length == 9 && value.regionMatches(true, start, "Negotiate", 0, 9);
    }
}
