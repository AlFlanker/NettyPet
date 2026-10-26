package dev.example.integrationhost;

import dev.example.proxy.api.ProxyOptions;
import dev.example.proxy.transport.ProxyEngine;
import java.time.Duration;
import java.util.List;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;

/**
 * Explicit alternative wiring: import this class to select the existing Netty handler.
 * It is deliberately not component-scanned and introduces no toggle property.
 */
public class LegacyHandlerConfiguration {
    @Bean(name = "integrationLegacyNettyEngine", destroyMethod = "close")
    public ProxyEngine legacyNettyEngine(HostProxyProperties properties) throws Exception {
        ProxyOptions options = new ProxyOptions(properties.upstream(), 2, 128, 16, 32,
                65536, 262144, Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofSeconds(10), properties.timeout(), Duration.ofSeconds(30), List.of());
        // This host demonstrates contract compatibility with the portable writer.
        // The existing app module retains its separately optimized native Jetty adapter.
        return new ProxyEngine(options, List.of(),
                (request, failure) -> ServletContextRequest.getServletContextRequest(request)
                        .getServletChannel().abort(failure));
    }

    @Bean
    public ProxyRequestHandler legacyProxyHandler(
            @Qualifier("integrationLegacyNettyEngine") ProxyEngine engine, HostProxyProperties properties) {
        return new LegacyProxyHandler(engine, properties);
    }
}
