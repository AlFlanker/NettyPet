package dev.example.proxy.core;

import java.net.URI;
import java.util.Objects;

/** Собирает адрес исходящего запроса, сохраняя исходное кодирование пути, параметров и узел назначения. */
public final class UpstreamUri {
    private UpstreamUri() {}

    /** Добавляет исходные путь и строку параметров к базовому пути сервера без декодирования. */
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
        // URI.resolve("//host/path") сменил бы узел назначения. Здесь ведущие // остаются
        // частью пути. Декодирование %2F, %25, '+' или нормализация '..' изменили бы
        // маршрутизацию сервера и подписи запросов, зависящие от исходного URI.
        String target = base.getScheme() + "://" + base.getRawAuthority() + prefix + rawPath
                + (rawQuery == null ? "" : "?" + rawQuery);
        URI result = URI.create(target);
        validate(result);
        return result;
    }

    /** Проверяет абсолютный HTTP(S) URI без логина, пароля и фрагмента с допустимым портом. */
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

    /** Возвращает путь и параметры для стартовой строки HTTP/1.1, сохраняя готовые %XX-последовательности. */
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
