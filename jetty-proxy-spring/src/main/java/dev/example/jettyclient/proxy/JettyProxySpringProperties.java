package dev.example.jettyclient.proxy;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Spring Boot binding adapter; transport validation remains in the plain core properties. */
@ConfigurationProperties("jetty.proxy")
public record JettyProxySpringProperties(
        @DefaultValue("http://127.0.0.1:9000") URI upstream,
        @DefaultValue("65536") int chunkBytes,
        @DefaultValue("16384") int maxResponseHeadersBytes,
        @DefaultValue("128") int maxConcurrent,
        @DefaultValue("64") int maxConnectionsPerDestination,
        @DefaultValue("64") int maxQueuedRequestsPerDestination,
        @DefaultValue("2") int selectors,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("60s") Duration exchangeTimeout,
        @DefaultValue("30s") Duration idleTimeout,
        @DefaultValue("60s") Duration destinationIdleTimeout,
        @DefaultValue Fetch fetch) {

    public JettyProxyProperties toCoreProperties() {
        return new JettyProxyProperties(upstream, chunkBytes, maxResponseHeadersBytes, maxConcurrent,
                maxConnectionsPerDestination, maxQueuedRequestsPerDestination, selectors,
                connectTimeout, exchangeTimeout, idleTimeout, destinationIdleTimeout);
    }

    public ProxyFetchOptions toFetchOptions() {
        return new ProxyFetchOptions(fetch.maxConcurrent(), fetch.decoderThreads(), fetch.maxQueued());
    }

    /** Separate full-body fetch budget; no body-related defaults are installed into the host application. */
    public record Fetch(@DefaultValue("8") int maxConcurrent,
                        @DefaultValue("2") int decoderThreads,
                        @DefaultValue("8") int maxQueued) { }
}
