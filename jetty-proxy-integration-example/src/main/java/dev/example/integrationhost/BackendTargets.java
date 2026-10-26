package dev.example.integrationhost;

import java.net.URI;

/** Joins encoded path/query to a trusted configured authority without URI.resolve authority changes. */
final class BackendTargets {
    private BackendTargets() { }

    static URI resolve(URI base, String pathAndQuery) {
        URI relative = URI.create(pathAndQuery);
        if (relative.isAbsolute() || relative.getRawAuthority() != null || relative.getRawFragment() != null
                || relative.getRawPath() == null || !relative.getRawPath().startsWith("/"))
            throw new IllegalArgumentException("Resource path must be an encoded origin-relative path");
        String prefix = base.getRawPath();
        if (prefix == null || prefix.equals("/")) prefix = "";
        else if (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        URI target = URI.create(base.getScheme() + "://" + base.getRawAuthority() + prefix + relative);
        return URI.create(target.toASCIIString());
    }
}
