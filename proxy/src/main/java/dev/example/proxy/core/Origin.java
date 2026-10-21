package dev.example.proxy.core;

import java.net.URI;
import java.util.Locale;

/**
 * Нормализованный ключ списка разрешённых назначений и пула соединений: схема,
 * имя узла и фактический порт. Он проверяет имя назначения, но не фильтрует DNS/IP.
 * Адрес IPv6 хранится без скобок; для адресной части URI и Host скобки добавляются.
 */
public record Origin(String scheme, String host, int port) {
    /** Приводит схему и узел к единому виду и отклоняет неподдерживаемую схему или порт. */
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

    /** Проверяет URI и подставляет порт 80 или 443, если порт не указан явно. */
    public static Origin from(URI uri) {
        UpstreamUri.validate(uri);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
        return new Origin(scheme, uri.getHost(), port);
    }

    public boolean secure() { return scheme.equals("https"); }

    /** Формирует значение Host: заключает IPv6 в скобки и опускает стандартный порт схемы. */
    public String authority() {
        String authorityHost = host.indexOf(':') >= 0 ? '[' + host + ']' : host;
        boolean defaultPort = secure() ? port == 443 : port == 80;
        return defaultPort ? authorityHost : authorityHost + ':' + port;
    }
}
