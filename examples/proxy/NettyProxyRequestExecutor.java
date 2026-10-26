package com.otr.portal.proxy;

import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.HostsConfiguration;
import com.otr.portal.hosts.filter.BackendForRequestFilter;
import com.otr.portal.metadata.reloadable.ConfigurationService;
import com.otr.portal.metadata.reloadable.ReloadableConfiguration;
import com.otr.portal.proxy.caching.CachedResponse;
import com.otr.portal.proxy.caching.CachePublicationQueue;
import com.otr.portal.proxy.caching.ContentCachingResponse;
import com.otr.portal.proxy.caching.CachedResponseWriter;
import com.otr.portal.proxy.caching.SharedResponseCache;
import com.otr.portal.proxy.headers.DefaultHeaderProcessor;
import com.otr.portal.proxy.logging.ProxiedRequestLifecycle;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * @author pobedenniy.alexey
 * @since 30.11.17
 */
@Slf4j
@Component("nettyRequestExecutor")
public class NettyProxyRequestExecutor implements ProxyRequestExecutor {
    private final SharedResponseCache responseCache = new SharedResponseCache();
    // Snapshot construction can copy up to 8 MiB; keep it off transport/callback threads.
    // Pending jobs retain capture reservations until published or discarded.
    private final CachePublicationQueue cachePublisher = new CachePublicationQueue(responseCache);

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
    public void requestCacheHouseKeeping() { responseCache.cleanUp(); }

    @PreDestroy
    public void closeCachePublisher() { cachePublisher.close(); }

    private boolean isUriForbidden(String uri, Backend backend) {
        final String requestURI = StringUtils.removeStart(uri, '/' + backend.getUri());
        return backend
                .getUriOverride(requestURI)
                .map(ov -> ov.matches(requestURI) && ov.isForbid())
                .orElse(false);
    }

    private boolean shouldCache(HttpServletRequest req, Backend backend) {
        final String requestURI = StringUtils.removeStart(req.getRequestURI(), '/' + backend.getUri());
        return SharedResponseCache.eligibleRequest(req) && backend.shouldCache(requestURI);
    }

    private void loadCached(Backend backend, HttpServletRequest req, HttpServletResponse resp, AsyncContext asyncContext, RequestCallback callback, ProxiedRequestLifecycle lifecycle) throws IOException {
        final String cacheKey = SharedResponseCache.key(backend.getName(), req.getRequestURI(), req.getQueryString());
        CachedResponse cached = responseCache.get(cacheKey);
        if (cached == null) {
            loadAndCache(cacheKey, backend, req, resp, asyncContext, callback, lifecycle);
            return;
        }
        lifecycle.loadedFromCache();
        resp.setStatus(cached.getStatus());
        defaultHeaderProcessor.consumeHeaders(cached.getHeaders(), req, resp, backend);
        byte[] bytes = cached.getContent();
        // An interrupted hit must remain observably incomplete instead of looking like a complete short body.
        resp.setContentLength(bytes.length);
        // The old HttpProxy starts async with timeout=0. Cache hits have no backend command deadline.
        asyncContext.setTimeout(backend.getTimeout() > 0 ? backend.getTimeout() : 30_000L);
        CachedResponseWriter.write(asyncContext, resp, bytes, callback::requestCompleted);
    }

    private void loadAndCache(String cacheKey, Backend backend, HttpServletRequest req, HttpServletResponse resp,
            AsyncContext asyncContext, RequestCallback callback, ProxiedRequestLifecycle lifecycle) {
        ContentCachingResponse capture = new ContentCachingResponse(resp);
        asyncContext.addListener(new AsyncListener() {
            @Override public void onComplete(AsyncEvent event) { /* the command publishes its terminal result */ }
            @Override public void onError(AsyncEvent event) { capture.discardCapture(); }
            @Override public void onTimeout(AsyncEvent event) { capture.discardCapture(); }
            @Override public void onStartAsync(AsyncEvent event) { capture.discardCapture(); }
        });
        try {
            proxyCommandFactory.createCommand(backend, req, capture, asyncContext, lifecycle).execute()
                    .doOnNext(status -> publish(cacheKey, capture, status == HttpServletResponse.SC_OK))
                    .doOnError(failure -> capture.discardCapture())
                    .doOnCancel(capture::discardCapture)
                    .subscribe(status -> callback.requestCompleted());
        } catch (RuntimeException | Error failure) {
            capture.discardCapture();
            throw failure;
        }
    }

    private void publish(String cacheKey, ContentCachingResponse capture, boolean successful) {
        cachePublisher.publish(cacheKey, capture, successful).whenComplete((result, failure) -> {
            if (failure != null) log.warn("Не удалось сохранить ответ в кэше для ключа {}", cacheKey, failure);
        });
    }
}
