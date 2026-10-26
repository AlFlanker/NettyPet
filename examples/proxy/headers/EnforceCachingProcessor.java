package com.otr.portal.proxy.headers;

import com.google.common.collect.Multimap;
import com.otr.portal.core.model.http.Header;
import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * @author pobedenniy.alexey
 * @since 13.10.16
 */
public class EnforceCachingProcessor implements HeaderProcessor {
    private Integer cachePeriod;
    private HeaderProcessor headerProcessor;

    public EnforceCachingProcessor(Integer cachePeriod, HeaderProcessor headerProcessor) {
        this.cachePeriod = cachePeriod;
        this.headerProcessor = headerProcessor;
    }

    @Override
    public Stream<Header> collectHeaders(HttpServletRequest req, Backend backend) {
        return headerProcessor.collectHeaders(req, backend);
    }

    @Override
    public void consumeHeaders(Multimap<String, String> headers, HttpServletRequest req, HttpServletResponse resp, Backend backend) {
        headerProcessor.consumeHeaders(headers, req, resp, backend);
        if (!headers.containsKey(HttpHeaders.CACHE_CONTROL)) {
            resp.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(cachePeriod, TimeUnit.SECONDS).getHeaderValue());
        }
    }
}
