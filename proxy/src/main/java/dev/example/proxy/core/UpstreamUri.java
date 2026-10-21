package dev.example.proxy.core;

import java.net.URI;
import java.util.Objects;

/** Сборка request-target без изменения значения raw path/query и без смены authority. */
public final class UpstreamUri {
    private UpstreamUri() {}

    /** Добавляет исходный raw path/query к необязательному базовому префиксу upstream. */
    public static URI resolve(URI base, String rawPath, String rawQuery) {
        validate(base);
        if (base.getRawQuery() != null) {
            throw new IllegalArgumentException("The upstream base URI must not contain a query");
        }
        if (rawPath == null || !rawPath.startsWith("/") || rawPath.indexOf('?') >= 0
                || rawPath.indexOf('#') >= 0 || (rawQuery != null && rawQuery.indexOf('#') >= 0)) {
            throw new IllegalArgumentException("Expected an origin-form request path and raw query");
        }
        String prefix = base.getRawPath();
        if (prefix == null || prefix.equals("/")) prefix = "";
        else if (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        // URI.resolve("//host/path") сменил бы authority. Здесь ведущие // остаются
        // частью пути. Не декодируем %2F, %25, '+' и не нормализуем '..': это изменило
        // бы маршрутизацию backend и нарушило подписи запросов, зависящие от raw URI.
        String target = base.getScheme() + "://" + base.getRawAuthority() + prefix + rawPath
                + (rawQuery == null ? "" : "?" + rawQuery);
        URI result = URI.create(target);
        validate(result);
        return result;
    }

    public static URI validate(URI uri) {
        Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Expected an absolute HTTP(S) URI without user info or fragment");
        }
        return uri;
    }

    public static String requestTarget(URI uri) {
        validate(uri);
        // Кодируем лишь ещё не закодированный Unicode. Готовые %XX сохраняются;
        // многокомпонентный конструктор URI закодировал бы '%' повторно.
        URI ascii = URI.create(uri.toASCIIString());
        String path = ascii.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return path + (ascii.getRawQuery() == null ? "" : "?" + ascii.getRawQuery());
    }
}
