package dev.example.proxy.core;

import java.net.URI;
import java.util.Locale;

/**
 * Нормализованный ключ allowlist и пула: scheme + host + фактический port.
 * Это проверка имени назначения, не DNS/IP-фильтрация. IPv6 хранится без скобок;
 * скобки добавляются только при построении HTTP authority/Host.
 */
public record Origin(String scheme, String host, int port) {
    public Origin {
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http and https origins are supported");
        }
        if (host.isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid origin host or port");
        }
    }

    public static Origin from(URI uri) {
        UpstreamUri.validate(uri);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
        return new Origin(scheme, uri.getHost(), port);
    }

    public boolean secure() { return scheme.equals("https"); }

    public String authority() {
        String authorityHost = host.indexOf(':') >= 0 ? '[' + host + ']' : host;
        boolean defaultPort = secure() ? port == 443 : port == 80;
        return defaultPort ? authorityHost : authorityHost + ':' + port;
    }
}
