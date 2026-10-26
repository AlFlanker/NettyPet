package dev.example.jettyclient.proxy.policies;

import dev.example.jettyclient.proxy.ProxyHeaderCustomizer;
import dev.example.jettyclient.proxy.ProxyHeaderEditor;
import dev.example.jettyclient.proxy.ProxyRequestMetadata;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Header-only URL mapping prepared on the caller's thread. No Servlet, session,
 * cookie store or external I/O is captured. Cookie/Set-Cookie values are left to
 * the application's cookie policy and the engine's independent field forwarding.
 */
public final class HeaderRewriteCustomizer implements ProxyHeaderCustomizer {
    private final LinkRewriteSettings settings;
    private final URI frontendRequestUri;
    private final boolean rewriteLinks;

    public HeaderRewriteCustomizer(LinkRewriteSettings settings, URI frontendRequestUri, boolean rewriteLinks) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.frontendRequestUri = Objects.requireNonNull(frontendRequestUri, "frontendRequestUri");
        if (!sameOrigin(frontendRequestUri, settings.frontendBase()) || frontendRequestUri.getRawUserInfo() != null
                || frontendRequestUri.getRawFragment() != null)
            throw new IllegalArgumentException("frontendRequestUri must belong to the configured frontend origin");
        this.rewriteLinks = rewriteLinks;
    }

    @Override public void customizeRequest(ProxyRequestMetadata request, ProxyHeaderEditor headers) {
        // Absence of Accept-Encoding still permits a content coding; identity explicitly asks for rewriteable bytes.
        if (rewriteLinks && ContentCodingPreferences.identityAllowed(request.headerValues("Accept-Encoding")))
            headers.set("Accept-Encoding", "identity");
        map(headers, "Origin", value -> {
            URI origin = parse(value);
            if (origin == null || !sameOrigin(origin, settings.frontendBase()) || origin.getRawUserInfo() != null
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))) return value;
            return origin(settings.backendBase());
        });
        map(headers, "Referer", value -> {
            URI referer = parse(value);
            if (referer == null) return value;
            if (!referer.isAbsolute()) referer = frontendRequestUri.resolve(referer);
            return remap(referer, settings.frontendBase(), settings.backendBase(), value);
        });
    }

    @Override public void customizeResponse(ProxyRequestMetadata request, int status, ProxyHeaderEditor headers) {
        map(headers, "Location", value -> {
            URI location = parse(value);
            if (location == null) return value;
            if (location.isAbsolute()) return remap(location, settings.backendBase(), settings.frontendBase(), value);
            if (location.getRawAuthority() != null)
                return remap(URI.create(settings.backendBase().getScheme() + ":" + value),
                        settings.backendBase(), settings.frontendBase(), value);
            if (value.startsWith("/"))
                return remap(URI.create(origin(settings.backendBase()) + value),
                        settings.backendBase(), settings.frontendBase(), value);
            // An ordinary relative reference resolves against the corresponding frontend path in the browser.
            return value;
        });
    }

    private static void map(ProxyHeaderEditor headers, String name, java.util.function.UnaryOperator<String> mapping) {
        List<String> original = headers.values(name);
        if (original.isEmpty()) return;
        List<String> updated = original.stream().map(mapping).toList();
        if (original.equals(updated)) return;
        headers.remove(name);
        for (String value : updated) headers.add(name, value);
    }

    private static String remap(URI value, URI from, URI to, String unchanged) {
        if (!sameOrigin(value, from) || value.getRawUserInfo() != null) return unchanged;
        String prefix = path(from);
        String source = path(value);
        if (!source.equals(prefix) && !source.startsWith(prefix + "/")) return unchanged;
        String rewritten = path(to) + source.substring(prefix.length());
        return URI.create(origin(to) + rewritten + (value.getRawQuery() == null ? "" : "?" + value.getRawQuery())
                + (value.getRawFragment() == null ? "" : "#" + value.getRawFragment())).toASCIIString();
    }

    private static String path(URI uri) { return uri.getRawPath() == null ? "" : uri.getRawPath(); }
    private static String origin(URI uri) { return uri.getScheme() + "://" + uri.getRawAuthority(); }
    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme() != null && first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost() != null && first.getHost().equalsIgnoreCase(second.getHost()) && port(first) == port(second);
    }
    private static int port(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
    private static URI parse(String value) {
        try { return URI.create(value); }
        catch (IllegalArgumentException invalid) { return null; }
    }
}
