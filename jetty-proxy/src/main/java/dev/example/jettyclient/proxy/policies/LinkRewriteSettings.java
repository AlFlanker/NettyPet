package dev.example.jettyclient.proxy.policies;

import java.net.URI;
import java.util.Objects;

/**
 * Immutable application URL mapping and per-response budgets. Bases are absolute
 * HTTP(S) URLs without credentials, query or fragment; their final slash is
 * canonicalized. URL text is emitted as ASCII URI text, independent of body charset.
 */
public record LinkRewriteSettings(URI backendBase, URI frontendBase, long maxInputBytes, long maxOutputBytes) {
    public LinkRewriteSettings {
        backendBase = base(backendBase, "backendBase");
        frontendBase = base(frontendBase, "frontendBase");
        if (maxInputBytes < 1 || maxOutputBytes < 1)
            throw new IllegalArgumentException("Rewrite limits must be positive");
    }

    private static URI base(URI uri, String name) {
        Objects.requireNonNull(uri, name);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65_535)
            throw new IllegalArgumentException(name + " must be an HTTP(S) base URL without credentials/query/fragment");
        String text = uri.toASCIIString();
        if (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return URI.create(text);
    }
}
