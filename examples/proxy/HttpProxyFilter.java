package com.otr.portal.proxy;

import com.otr.portal.breaker.data.InterruptibleResource;
import com.otr.portal.breaker.service.BreakerResourceTypeRegistry;
import com.otr.portal.core.Configuration;
import com.otr.portal.core.internal.PathHelper;
import com.otr.portal.core.metrics.PortalResourcesRequestTimeMetricInterceptor;
import com.otr.portal.core.security.AuthenticationHelper;
import com.otr.portal.hosts.backends.BackendRegistry;
import com.otr.portal.metadata.reloadable.ConfigurationService;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.ViewResolver;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static com.otr.portal.breaker.controller.MaintenanceController.RESOURCE;
import static com.otr.portal.core.security.routes.CheckRoutesFilter.CHECK_ROUTES_FILTER_ORDER;
import static com.otr.portal.hosts.filter.BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME;

/**
 * Фильтр, осуществляющий проксирование запросов на удаленный узел
 *
 * @author pobedenniy.alexey
 * @since 27.06.16
 */
@SuppressWarnings("unused")
@Component
@Order(HttpProxyFilter.PROXY_FILTER_ORDER)
public class HttpProxyFilter extends OncePerRequestFilter implements PortalResourcesRequestTimeMetricInterceptor {
    private static final Logger log = LoggerFactory.getLogger(HttpProxyFilter.class);
    public static final int PROXY_FILTER_ORDER = CHECK_ROUTES_FILTER_ORDER << 1;

    private final AntPathMatcher antMatcher;

    private final HttpProxy httpProxy;
    private final PathHelper pathHelper;
    private String resourcePrefix;
    private BreakerResourceTypeRegistry breakerResourceRegistry;
    private final BackendRegistry backendRegistry;

    public HttpProxyFilter(HttpProxy httpProxy,
                           Configuration configuration,
                           PathHelper pathHelper,
                           AuthenticationHelper authenticationChecker,
                           ConfigurationService configurationService,
                           @Qualifier("viewResolver") ViewResolver viewResolver,
                           PrometheusMeterRegistry meterRegistry,
                           BreakerResourceTypeRegistry breakerResourceRegistry, BackendRegistry backendRegistry) {
        this.httpProxy = httpProxy;
        this.pathHelper = pathHelper;
        this.backendRegistry = backendRegistry;
        this.antMatcher = new AntPathMatcher();
        this.resourcePrefix = pathHelper.getResourcePrefix();
        this.breakerResourceRegistry = breakerResourceRegistry;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest req,
            @NonNull HttpServletResponse resp,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        if (shouldServeLocally(req)) {
            if (log.isTraceEnabled()) {
                log.trace("Обслуживаем локальный ресурс: {}", req.getRequestURI());
            }
            filterChain.doFilter(req, resp);
            return;
        }

        if (pageOnMaintenance(req, resp)) {
            return;
        }

        httpProxy.proxyRequest(req, resp);
    }
    /**
     *  ToDo: тут старая логика + isBackendNotDefined. По факту проверка
     *  и классфикация запроса в {@link com.otr.portal.hosts.filter.BackendForRequestFilter}
     *  уже решает проблему, кроме isLocalRequest() из этого класса
     */
    private boolean shouldServeLocally(HttpServletRequest req) {
        return isBackendNotDefined(req)
                && (isLocalRequest(req.getRequestURI()) || localServlet(req, req.getServletPath()));
    }

    private boolean isBackendNotDefined(HttpServletRequest req) {
        return req.getAttribute(BACKEND_ATTRIBUTE_NAME) == null;
    }

    @Override
    public boolean shouldMeasure(HttpServletRequest req) {
        return isLocalRequest(req.getRequestURI()) || localServlet(req, req.getServletPath());
    }

    private boolean localServlet(HttpServletRequest req, String requestURI) {
        Map<String, ? extends ServletRegistration> servletRegustrations = req.getServletContext().getServletRegistrations();
        for (Map.Entry<String, ? extends ServletRegistration> servletEntry : servletRegustrations.entrySet()) {
            for (String mapping : servletEntry.getValue().getMappings()) {
                if (antMatcher.matchStart(mapping, requestURI)) {
                    if (log.isTraceEnabled()) {
                        log.trace("Request to {} is request to servlet {}", req.getRequestURI(), servletEntry.getKey());
                    }
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * Определяет, является ли запрашиваемый ресурс локальным
     *
     * @param requestURI запрашиваемый ресур
     * @return true если ресурс локальный
     */
    private boolean isLocalRequest(String requestURI) {
        return requestURI.startsWith(resourcePrefix) || requestURI.equals("/favicon.ico");
    }

    private boolean pageOnMaintenance(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<InterruptibleResource> resource = breakerResourceRegistry.castToInterruptibleResource(request);

        if (resource.isPresent() && !resource.get().enableState()) {
            try {
                request.setAttribute(RESOURCE, resource);
                request.getServletContext().getRequestDispatcher(pathHelper.concatAndNormalizePath("/resource-maintenance")).forward(request, response);
                return true;
            } catch (Exception ex) {
                log.error("Ошибка отображения страницы информации об обслуживании страницы", ex);
                throw new IOException("Ошибка отображения страницы информации об обслуживании страницы");
            }
        }

        return false;
    }
}