package com.otr.portal.proxy;

import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.HostsConfiguration;
import com.otr.portal.hosts.filter.BackendForRequestFilter;
import com.otr.portal.metadata.reloadable.ConfigurationService;
import com.otr.portal.metadata.reloadable.ReloadableConfiguration;
import com.otr.portal.proxy.caching.CachedResponse;
import com.otr.portal.proxy.caching.ContentCachingResponse;
import com.otr.portal.proxy.headers.DefaultHeaderProcessor;
import com.otr.portal.proxy.logging.ProxiedRequestLifecycle;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author pobedenniy.alexey
 * @since 30.11.17
 */
@Slf4j
@Component("nettyRequestExecutor")
public class NettyProxyRequestExecutor implements ProxyRequestExecutor {
    private final ConcurrentMap<String, CachedResponse> responseCache = new ConcurrentHashMap<>();

    private final ProxyCommandFactory proxyCommandFactory;
    private final DefaultHeaderProcessor defaultHeaderProcessor;

    private final ReloadableConfiguration<HostsConfiguration>.ReloadableConfigurationProperty<Boolean> saveBackendInSession;

    public NettyProxyRequestExecutor(ProxyCommandFactory proxyCommandFactory, DefaultHeaderProcessor defaultHeaderProcessor, ConfigurationService configurationService) {
        this.proxyCommandFactory = proxyCommandFactory;
        this.defaultHeaderProcessor = defaultHeaderProcessor;
        this.saveBackendInSession = configurationService.getConfigurationProperty(HostsConfiguration.class,
                HostsConfiguration::isSaveBackendInSession);
    }

    @Override
    public void execute(HttpServletRequest req, HttpServletResponse resp, AsyncContext asyncContext, ProxiedRequestLifecycle lifecycle, RequestCallback callback) throws IOException, ServletException {
        Backend backend = (Backend) req.getAttribute(BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME);
        if (backend == null && saveBackendInSession.getValue()) {
//          EB-91497
            backend = (Backend) req.getSession().getAttribute(BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME);
        }
        if (backend == null) {
            log.debug("Не найден backend для запроса {}", req.getRequestURI());
            resp.addHeader("Reason", "Backend not found");
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            asyncContext.complete();
        } else {
            boolean forbidden = isUriForbidden(req.getRequestURI(), backend);
            if (forbidden) {
                resp.sendError(HttpStatus.FORBIDDEN.value(), "Доступ к странице запрещен настройками");
                asyncContext.complete();
                return;
            }
            boolean cache = shouldCache(req, backend);
            if (cache) {
                loadCached(backend, req, resp, asyncContext, callback, lifecycle);
            } else {
                // [Шаг 1] Начало проксирования; подписчик получит код ответа в шаге 8.3.
                proxyCommandFactory
                        .createCommand(backend, req, resp, asyncContext, lifecycle)
                        .execute()
                        .subscribe(i ->
                                callback.requestCompleted());
            }
        }
    }

    @Override
    public void execute(String uri, HttpServletRequest req, PrintToStringHttpServletResponse response, ProxiedRequestLifecycle lifecycle, RequestCallback callback) throws IOException, ServletException {
        Backend backend = (Backend) req.getAttribute(BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME);
        if (backend == null && saveBackendInSession.getValue()) {
            backend = (Backend) req.getSession().getAttribute(BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME);
        }
        if (backend == null) {
            log.debug("Не найден backend для запроса {}", req.getRequestURI());
            response.addHeader("Reason", "Backend not found");
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        } else {
            // [Шаг 1] Синхронный вариант: ответ собирается в строку, результат ожидается через block().
            proxyCommandFactory
                    .createCommand(backend, req, response, null, lifecycle)
                    .execute()
                    .doOnNext(i -> callback.requestCompleted())
                    .block();
        }
    }

    @Scheduled(fixedDelay = 1000 * 60 * 15)
    public void requestCacheHouseKeeping() {
        HashSet<Map.Entry<String, CachedResponse>> entries = new HashSet<>(responseCache.entrySet());
        for (Map.Entry<String, CachedResponse> entry : entries) {
            String key = entry.getKey();
            if (entry.getValue().invalid()) {
                responseCache.remove(key);
            }
        }
    }

    private boolean isUriForbidden(String uri, Backend backend) {
        final String requestURI = StringUtils.removeStart(uri, '/' + backend.getUri());
        return backend
                .getUriOverride(requestURI)
                .map(ov -> ov.matches(requestURI) && ov.isForbid())
                .orElse(false);
    }

    private boolean shouldCache(HttpServletRequest req, Backend backend) {
        final String requestURI = StringUtils.removeStart(req.getRequestURI(), '/' + backend.getUri());
        return HttpMethod.GET.name().equals(req.getMethod()) && backend.shouldCache(requestURI);
    }

    private void loadCached(Backend backend, HttpServletRequest req, HttpServletResponse resp, AsyncContext asyncContext, RequestCallback callback, ProxiedRequestLifecycle lifecycle) throws IOException {
        final String cacheKey = backend.getName() + ":" + req.getRequestURI();
        CachedResponse cached = responseCache.get(cacheKey);
        if (cached == null) {
            loadAndCache(cacheKey, backend, req, resp, asyncContext, callback, lifecycle);
        } else {
            if (cached.invalid()) {
                responseCache.remove(cacheKey);
                loadAndCache(cacheKey, backend, req, resp, asyncContext, callback, lifecycle);
                return;
            }
            lifecycle.loadedFromCache();
            resp.setStatus(cached.getStatus());
            defaultHeaderProcessor.consumeHeaders(cached.getHeaders(), req, resp, backend);
            byte[] bytes = cached.getContent();
            resp.getOutputStream().write(bytes);
            callback.requestCompleted();
            asyncContext.complete();
        }
    }

    private void loadAndCache(String cacheKey, Backend backend, HttpServletRequest req, HttpServletResponse resp, AsyncContext asyncContext, RequestCallback callback, ProxiedRequestLifecycle lifecycle) {
        ContentCachingResponse cachingResponse = new ContentCachingResponse(resp);
        // [Шаг 1] Вариант с кэшированием: тело копится в ContentCachingResponse и после шага 8.3 кладётся в кэш.
        proxyCommandFactory
                .createCommand(backend, req, cachingResponse, asyncContext, lifecycle)
                .execute()
                .doOnNext(i -> {
                    final CachedResponse response = cachingResponse.toCachedResponse();
                    if (response.getHeaders().containsKey(HttpHeaders.EXPIRES)) {
                        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                        final Collection<String> expiresValues = response.getHeaders().get(HttpHeaders.EXPIRES);
                        if (!expiresValues.isEmpty()) {
                            try {
                                Date expiresAt = format.parse(expiresValues.iterator().next());
                                response.setValidThrough(expiresAt);
                            } catch (ParseException e) {
                                log.warn("Ошибка парсинга хидера Expires для запроса \"{}\"", req.getRequestURI(), e);
                            }
                        }
                    } else if (response.getHeaders().containsKey(HttpHeaders.CACHE_CONTROL)) {
                        Collection<String> cacheControl = response.getHeaders().get(HttpHeaders.CACHE_CONTROL);
                        if (!cacheControl.isEmpty()) {
                            String headerValue = cacheControl.iterator().next().toLowerCase(Locale.ROOT);
                            Map<String, Boolean> flags = new HashMap<>();
                            int maxAge = 0;
                            for (String directive : headerValue.split(",")) {
                                String d = directive.trim();
                                int eq = d.indexOf('=');
                                var name = (eq >= 0
                                        ? d.substring(0, eq)
                                        : d)
                                        .trim().toLowerCase(Locale.ROOT);
                                var value = eq >= 0
                                        ? d.substring(eq + 1).trim()
                                        : null;
                                switch (name) {
                                    case "no-cache" -> flags.put("no-cache", true);
                                    case "no-store" -> flags.put("no-store", true);
                                    case "max-age" -> {
                                        try {
                                            maxAge = Integer.parseInt(value);
                                        } catch (NumberFormatException ex) {
                                            log.debug("некорректный max-age:", ex);
                                            // некорректный max-age - игнорируем
                                        }
                                    }
                                }
                            }
                            if (!flags.containsKey("no-cache")
                                    && !flags.containsKey("no-store") && maxAge > 0) {
                                response.setValidThrough(new Date(System.currentTimeMillis() + maxAge * 1000L));
                            }
                        }
                    }
                    responseCache.put(cacheKey, response);
                })
                .subscribe(i ->
                        callback.requestCompleted());
    }
}
