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
 * Неизменяемый снимок после Processor: выбор пула, Host и request-target всегда
 * используют один URI. Сохранённая расширением ссылка на ProxyRequest уже не влияет на I/O.
 */
record PreparedRequest(String method, URI uri, Map<String, List<String>> headers,
                       long contentLength, boolean declaredLength) {
    static PreparedRequest capture(HttpServletRequest incoming, ProxyRequest mutable) {
        URI uri = UpstreamUri.validate(mutable.uri());
        var headers = HeaderRules.requestSnapshot(mutable.headers());
        // Общий пул не привязан к downstream-клиенту. NTLM/Negotiate могут закрепить
        // identity за TCP-соединением и передать его другому клиенту при reuse.
        // Проверяем итоговые заголовки: Processor может как добавить, так и заменить auth.
        for (String authorization : headers.getOrDefault("authorization", List.of())) {
            if (connectionBoundAuthentication(authorization))
                throw new ProxyRejection(501, "Connection-bound authentication is unsupported");
        }
        long declared = incoming.getContentLengthLong();
        long length = declared;
        // В HTTP/1 отсутствие CL и TE означает отсутствие тела. В HTTP/2 длина может
        // оставаться неизвестной до END_STREAM: такое тело передаём upstream chunked.
        if (length < 0 && incoming.getProtocol().startsWith("HTTP/1.")
                && incoming.getHeader("Transfer-Encoding") == null) length = 0;
        return new PreparedRequest(incoming.getMethod(), uri,
                headers, length, declared >= 0);
    }

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
