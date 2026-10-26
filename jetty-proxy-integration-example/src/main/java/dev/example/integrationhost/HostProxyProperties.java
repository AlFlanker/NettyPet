package dev.example.integrationhost;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Application routing is separate from transport configuration in jetty.proxy. */
@ConfigurationProperties("host.proxy")
public record HostProxyProperties(
        @DefaultValue("http://127.0.0.1:9000/base") URI upstream,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("false") boolean maintenance) {
    public HostProxyProperties {
        if (upstream == null || upstream.getHost() == null || upstream.getRawUserInfo() != null
                || upstream.getRawQuery() != null || upstream.getRawFragment() != null
                || !("http".equalsIgnoreCase(upstream.getScheme()) || "https".equalsIgnoreCase(upstream.getScheme()))
                || upstream.getPort() == 0 || upstream.getPort() > 65535)
            throw new IllegalArgumentException("host.proxy.upstream must be a trusted HTTP(S) base URI");
        if (timeout == null || timeout.isNegative() || timeout.toMillis() < 1)
            throw new IllegalArgumentException("host.proxy.timeout must be positive");
        timeout.toNanos();
    }
}
