package com.otr.portal.proxy.headers;

import com.google.common.base.Joiner;
import com.google.common.collect.Multimap;
import com.otr.portal.core.model.http.Header;
import com.otr.portal.hosts.Backend;
import com.otr.portal.proxy.cookies.Cookie;
import com.otr.portal.proxy.cookies.CookieInterceptor;
import com.otr.portal.proxy.cookies.CookieProcessor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static java.util.stream.Stream.of;
import static org.springframework.util.CollectionUtils.isEmpty;

/**
 * @author pobedenniy.alexey
 * @since 11.10.16
 */
@Service
public class DefaultHeaderProcessor implements HeaderProcessor {
    private static final Logger log = LoggerFactory.getLogger(DefaultHeaderProcessor.class);
    private static final Logger logHeaders = LoggerFactory.getLogger("HttpProxy.headers");
    private static final Logger logHeadersRaw = LoggerFactory.getLogger("HttpProxy.headers.raw");
    private static final Logger logCookies = LoggerFactory.getLogger("HttpProxy.cookies");
    private static final Logger logCookiesRaw = LoggerFactory.getLogger("HttpProxy.cookies.raw");

    private final List<HeaderInterceptor> headerInterceptors;
    private final List<CookieInterceptor> cookieInterceptors;
    private final List<CookieProcessor> cookieProcessors;

    public DefaultHeaderProcessor(@Autowired(required = false) List<HeaderInterceptor> headerInterceptors,
                                  @Autowired(required = false) List<CookieInterceptor> cookieInterceptors,
                                  @Autowired(required = false) List<CookieProcessor> cookieProcessors) {
        this.headerInterceptors = headerInterceptors;
        this.cookieInterceptors = ofNullable(cookieInterceptors).orElseGet(Collections::emptyList);
        this.cookieProcessors = cookieProcessors;
    }

    @Override
    public Stream<Header> collectHeaders(HttpServletRequest req, Backend backend) {
        Stream<String> headersStream = Collections
                .list(req.getHeaderNames())
                .stream();

        if (logHeadersRaw.isTraceEnabled()) {
            Collections
                    .list(req.getHeaderNames())
                    .forEach(e -> this.logHeader(createHeader(e, req.getHeader(e), backend), logHeadersRaw, logCookiesRaw));
        }
        if (backend.isReplaceUrlInContent()) {
            headersStream = headersStream.filter(header -> !header.equalsIgnoreCase(HttpHeaders.ACCEPT_ENCODING));
        }

        Stream<Header> headers =
                headersStream
                        //куки обрабатываем отдельно
                        .filter(headerName -> !HttpHeaders.COOKIE.equalsIgnoreCase(headerName))
                        .map(headerName -> createHeader(headerName, req.getHeader(headerName), backend));
        Stream<? extends Cookie> cookiesStream = of(ofNullable(req.getHeader(HttpHeaders.COOKIE)).orElse(""))
                .flatMap(h -> of(StringUtils.splitByWholeSeparator(h, "; ")))
                .map(Cookie::parse);
        for (CookieInterceptor interceptor : cookieInterceptors) {
            cookiesStream = interceptor.cookiesForBackend(cookiesStream, req, backend);
        }
        for (CookieProcessor processor : cookieProcessors) {
            cookiesStream = processor.process(cookiesStream);
        }

        String cookieHeader = cookiesStream
                .map(el -> el.name() + "=" + el.value())
                .collect(Collectors.joining("; "));

        Stream<Header> cookiePart =
                ofNullable(cookieHeader.isEmpty() ? null : cookieHeader)
                        .map(this::createCookieHeader)
                        .map(Stream::of)
                        .orElse(Stream.empty());


        Stream<Header> additionalHeaders = isEmpty(headerInterceptors) ? Stream.empty() :
                headerInterceptors.stream().flatMap(i -> i.headersForBackend(req, backend));
        logHeaders.trace("Headers for request {}", req.getRequestURI());
        return Stream.concat(Stream.concat(headers, cookiePart), additionalHeaders).peek(h -> this.logHeader(h, logHeaders, logCookies));
    }

    private <R> String processCookieHeader(Cookie cookie) {
        return cookie.name() + "=" + cookie.value();
    }

    private void logHeader(Header header, Logger logHeaders, Logger logCookies) {
        logHeaders.trace("[F->B] {}: {}", header.getName(), header.getValue());
        if (header.getName().equalsIgnoreCase(HttpHeaders.COOKIE) && logCookies.isTraceEnabled()) {
            logCookies.trace("[F->B] Cookies\n" + Joiner.on("\n").join(header.getValue().split(";")));
        }
    }

    @Override
    public void consumeHeaders(Multimap<String, String> headers, HttpServletRequest req, HttpServletResponse resp, Backend backend) {
        final List<String> cookies = new ArrayList<>(4);
        final List<Map.Entry<String, String>> otherHeaders = new ArrayList<>();
        headers
                .asMap()
                .entrySet()
                .stream()
                .flatMap(entry -> entry.getValue().stream().map(value -> new AbstractMap.SimpleEntry<>(entry.getKey(), value)))
                .filter(entry -> !entry.getKey().equalsIgnoreCase("X-Frame-Options"))
                .forEach(e -> {
                    if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(e.getKey())) {
                        cookies.add(e.getValue());
                    } else {
                        otherHeaders.add(e);
                    }
                });
        Stream<String> processedCookies = cookies.stream();
        for (CookieInterceptor cookieInterceptor : cookieInterceptors) {
            processedCookies = cookieInterceptor.cookieFromBackend(req, resp, processedCookies, backend);
        }
        Stream<Map.Entry<String, String>> processedHeaders = otherHeaders.stream();
        for (HeaderInterceptor headerInterceptor : headerInterceptors) {
            processedHeaders = headerInterceptor.headersFromBackend(req, resp, processedHeaders, backend);
        }
        Stream
                .concat(processedCookies.map(v -> new AbstractMap.SimpleEntry<>(HttpHeaders.SET_COOKIE, v)), processedHeaders)
                .forEach(entry -> {
                    final String headerName = entry.getKey();
                    logHeaders.trace("[B->F] {}: {}", headerName, entry.getValue());
                    //если сервер отдает в заголовке дату - то устанавливаем ее, заменяя тот заголовок, который устанавливает сам jetty
                    if (headerName.equalsIgnoreCase(HttpHeaders.DATE)) {
                        resp.setHeader(headerName, entry.getValue());
                    } else {
                        resp.addHeader(headerName, entry.getValue());
                    }
                });
        log.trace("Done processing headers");
    }

    private Header createCookieHeader(String value) {
        return new Header(HttpHeaders.COOKIE, value);
    }

    private Header createHeader(String headerName, String headerValue, Backend backend) {
        if (headerName.equalsIgnoreCase(HttpHeaders.HOST)) {
            // корректно заполняем HOST
            String authority = URI.create(backend.getBackendUrl()).getAuthority();
            return new Header(headerName, authority);
        }
        if (headerName.equalsIgnoreCase(HttpHeaders.ORIGIN) && backend.isReplaceOriginHeader()) {
            return new Header(headerName, backend.fullBackendUrl("/"));
        }
        if (headerName.equalsIgnoreCase(HttpHeaders.REFERER)) {
            return new Header(headerName, backend.frontendToBackendUrl(headerValue));
        }
        return new Header(headerName, headerValue);
    }

    private String traceCookie(String cookie) {
        log.trace("Copying cookie from browser request {} ", cookie);
        return cookie;
    }
}
