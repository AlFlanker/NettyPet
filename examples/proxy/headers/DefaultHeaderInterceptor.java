package com.otr.portal.proxy.headers;

import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.HostsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * @author pobedenniy.alexey
 * @since 20.03.19
 */
@Component
public class DefaultHeaderInterceptor implements HeaderInterceptor {
    private static final Logger log = LoggerFactory.getLogger(DefaultHeaderInterceptor.class);
    private final HostsService hostsService;

    public DefaultHeaderInterceptor(HostsService hostsService) {
        this.hostsService = hostsService;
    }

    @Override
    public Stream<Map.Entry<String, String>> headersFromBackend(HttpServletRequest req, HttpServletResponse resp, Stream<Map.Entry<String, String>> headers, Backend backend) {
        return headers
                .map(e -> processLocationHeader(e, backend));
    }

    private Map.Entry<String, String> processLocationHeader(Map.Entry<String, String> entry, Backend backend) {
        final String key = entry.getKey();
        if (HttpHeaders.LOCATION.equalsIgnoreCase(key)) {
            final String value = entry.getValue();
            Optional<String> rewrittenUrl = hostsService.frontendUrlFromBackendUrl(backend, value);
            if (log.isDebugEnabled() && rewrittenUrl.isPresent()) {
                log.debug("Rewriting Set-Header location from {} to {}", value, rewrittenUrl.get());
            }
            return new AbstractMap.SimpleEntry<>(key, rewrittenUrl.orElse(value));
        }
        return entry;
    }

}
