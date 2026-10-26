package dev.example.jettyclient.proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;

/** Небольшие правила HTTP для примера, без декодирования URI и преобразования тела. */
final class ProxyHeaders {
    private static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-connection",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    private ProxyHeaders() { }

    static URI target(URI upstream, HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!(path.equals("/proxy") || path.startsWith("/proxy/")))
            throw new IllegalArgumentException("Expected /proxy or /proxy/*");
        path = path.length() == 6 ? "/" : path.substring(6);
        String prefix = upstream.getRawPath();
        if (prefix == null || prefix.equals("/")) prefix = "";
        else if (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        String query = request.getQueryString();
        // URI.resolve would interpret a leading // as another authority.
        URI target = URI.create(upstream.getScheme() + "://" + upstream.getRawAuthority() + prefix + path
                + (query == null ? "" : "?" + query));
        return URI.create(target.toASCIIString());
    }

    static Map<String, List<String>> snapshot(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames()))
            headers.put(name, Collections.list(request.getHeaders(name)));
        return headers;
    }

    static HttpFields request(ProxyRequestMetadata metadata, List<ProxyHeaderCustomizer> customizers) {
        HttpFields.Mutable source = HttpFields.build();
        metadata.headers().forEach((name, values) -> values.forEach(value -> source.add(name, value)));
        Set<String> excluded = excluded(source.getValuesList("Connection"));
        excluded.addAll(Set.of("host", "content-length", "expect"));
        HttpFields prepared = sanitize(source, excluded);
        if (customizers.isEmpty()) return prepared;
        ProxyHeaderEditor editor = new ProxyHeaderEditor(prepared);
        try {
            for (ProxyHeaderCustomizer customizer : customizers) customizer.customizeRequest(metadata, editor);
            // Source Connection nominations remain excluded even when a hook re-adds them.
            return sanitize(editor.freeze(), excluded);
        } catch (Exception | AssertionError | LinkageError failure) {
            throw new CustomizationFailure(failure);
        } finally {
            editor.close();
        }
    }

    static HttpFields response(ProxyRequestMetadata metadata, int status, HttpFields source,
            List<ProxyHeaderCustomizer> customizers) {
        Set<String> excluded = excluded(source.getValuesList("Connection"));
        HttpFields prepared = sanitize(source, excluded);
        if (customizers.isEmpty()) return prepared;
        ProxyHeaderEditor editor = new ProxyHeaderEditor(prepared);
        try {
            for (ProxyHeaderCustomizer customizer : customizers)
                customizer.customizeResponse(metadata, status, editor);
            return sanitize(editor.freeze(), excluded);
        } catch (Exception | AssertionError | LinkageError failure) {
            throw new CustomizationFailure(failure);
        } finally {
            editor.close();
        }
    }

    static void applyResponse(HttpFields source, HttpServletResponse target) {
        Set<String> seen = new HashSet<>();
        for (HttpField field : source) {
            String lower = field.getName().toLowerCase(Locale.ROOT);
            // A preceding Filter may already have issued an application/session cookie.
            // Set-Cookie fields are independent and must never replace or join each other.
            if (lower.equals("set-cookie")) target.addHeader(field.getName(), field.getValue());
            else if (seen.add(lower)) target.setHeader(field.getName(), field.getValue());
            else target.addHeader(field.getName(), field.getValue());
        }
    }

    /** Applies protocol rules to the final sanitized/customized fields; 0 means forward. */
    static int prepareForwarding(String method, HttpFields.Mutable headers) {
        // A shared connection must never acquire an identity that outlives this request.
        for (String authorization : headers.getValuesList("Authorization")) {
            String value = authorization.stripLeading();
            int end = 0;
            while (end < value.length() && value.charAt(end) != ' ' && value.charAt(end) != '\t') end++;
            String scheme = value.substring(0, end);
            if (scheme.equalsIgnoreCase("NTLM") || scheme.equalsIgnoreCase("Negotiate")) return 501;
        }
        if (!method.equals("OPTIONS") && !method.equals("TRACE")) return 0;
        List<String> values = headers.getValuesList("Max-Forwards");
        if (values.isEmpty()) return 0;
        if (values.size() != 1) return 400;
        String value = values.getFirst().trim();
        if (value.isEmpty()) return 400;
        // Accept arbitrary-length 1*DIGIT values without overflow or a BigInteger allocation.
        // RFC 9110 permits capping the decremented result to our maximum supported value.
        long count = 0;
        for (int i = 0; i < value.length(); i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9') return 400;
            count = Math.min((long)Integer.MAX_VALUE + 1, count * 10 + digit - '0');
        }
        if (count == 0) return method.equals("OPTIONS") ? 200 : 501;
        headers.put("Max-Forwards", Long.toString(count - 1));
        return 0;
    }

    private static HttpFields sanitize(HttpFields source, Set<String> sourceExcluded) {
        Set<String> excluded = new HashSet<>(sourceExcluded);
        excluded.addAll(excluded(source.getValuesList("Connection")));
        HttpFields.Mutable result = HttpFields.build();
        for (HttpField field : source) {
            if (!excluded.contains(field.getName().toLowerCase(Locale.ROOT))) result.add(field);
        }
        return result.asImmutable();
    }

    static boolean hasToken(HttpServletRequest request, String header, String token) {
        return Collections.list(request.getHeaders(header)).stream()
                .flatMap(value -> List.of(value.split(",")).stream())
                .anyMatch(value -> value.trim().equalsIgnoreCase(token));
    }

    static boolean supportedTransferEncoding(List<String> values) {
        return values.isEmpty() || (values.size() == 1 && values.getFirst().trim().equalsIgnoreCase("chunked"));
    }

    private static Set<String> excluded(List<String> connections) {
        Set<String> result = new HashSet<>(HOP);
        for (String value : connections) {
            for (String token : value.split(",")) {
                if (!token.isBlank()) result.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    static final class CustomizationFailure extends RuntimeException {
        CustomizationFailure(Throwable cause) { super("Proxy header customizer failed", cause); }
    }
}
