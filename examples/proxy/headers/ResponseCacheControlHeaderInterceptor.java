package com.otr.portal.proxy.headers;

import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.AbstractMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class ResponseCacheControlHeaderInterceptor implements HeaderInterceptor {
    private static final Logger log = LoggerFactory.getLogger(DefaultHeaderProcessor.class);
    private static final Pattern MAX_AGE_PATTERN = Pattern.compile("max-age=(\\d+)");
    @Override
    public Stream<Map.Entry<String, String>> headersFromBackend(HttpServletRequest req, HttpServletResponse resp, Stream<Map.Entry<String, String>> headers, Backend backend) {
        return headers
                .map(header -> processCacheControlHeader(header, backend, req));

    }

    private Map.Entry<String, String> processCacheControlHeader(Map.Entry<String, String> entry, Backend backend, HttpServletRequest req) {
        final String key = entry.getKey();
        if (HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(key)) {
            final String requestURI = StringUtils.removeStart(req.getRequestURI(), '/' + backend.getUri());
            Integer newMaxAge = backend.getMaxAge(requestURI);
            if (newMaxAge == null) {
                return entry;
            }
            final String oldCacheControlValue = entry.getValue();
            Matcher matcher = MAX_AGE_PATTERN.matcher(oldCacheControlValue);
            if (matcher.find()) {
                String newCacheControlValue = matcher.replaceFirst("max-age=" + newMaxAge);
                if (log.isDebugEnabled()) {
                    log.debug("Rewriting max-age in cache-control from {} to {}", oldCacheControlValue, newCacheControlValue);
                }
                return new AbstractMap.SimpleEntry<>(key, newCacheControlValue);
            }
        }
        return entry;
    }
}
