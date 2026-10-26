package com.otr.portal.proxy;

import com.otr.portal.core.Configuration;
import com.otr.portal.proxy.logging.ProxiedRequestLifecycle;
import com.otr.portal.proxy.metrics.BackendsMetrics;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

import java.io.IOException;
import java.util.HashMap;

/**
 * Класс, инициирующий загрузку удаленного контента, для использования внутри шаблонов thymeleaf
 *
 * @author pobedenniy.alexey
 * @since 09.06.16
 */
@SuppressWarnings("unused")
@Component("httpProxy")
public class HttpProxy {
    private static final Logger log = LoggerFactory.getLogger(HttpProxy.class);
    static final String NOTAVAILABLE_VIEW_NAME = "notavailable";
    static final String MAINTENANCE_VIEW_NAME = "maintenance";
    public static final String THREAD_POOL_KEY = "HttpProxy";

    private BackendsMetrics metrics;
    private ViewResolver viewResolver;
    private Configuration configuration;
    private ProxyRequestExecutorLookup proxyRequestExecutor;


    public HttpProxy(Configuration configuration,
                     @Qualifier("viewResolver") ViewResolver viewResolver,
                     ProxyRequestExecutorLookup proxyRequestExecutor,
                     BackendsMetrics metrics) {
        this.configuration = configuration;
        this.viewResolver = viewResolver;
        this.proxyRequestExecutor = proxyRequestExecutor;
        this.metrics = metrics;
    }

    public void load(String path, HttpServletRequest originalRequest) throws Exception {
        load(path, originalRequest, null);
    }


    /**
     * Выполняет загрузку удаленного ресурса по переданному URL path
     * При этом выполняет копирование заголовков из исходного запроса originalRequest
     *
     * @param path            ресур для загрузки. Полный путь к ресурсу получается объединением аргумента path и пути к бэкенду
     * @param originalRequest исходный запрос
     * @return строковое представление загруженного ресурса
     * @throws IOException
     */
    public String load(String path, HttpServletRequest originalRequest, @Nullable HttpServletResponse originalResponse) throws Exception {
        try {
            ProxiedRequestLifecycle lifeCycle = new ProxiedRequestLifecycle(originalRequest, metrics).receivedRequest();
            final PrintToStringHttpServletResponse response = new PrintToStringHttpServletResponse();
            proxyRequestExecutor.forRequest(path).execute(path, originalRequest, response, lifeCycle, () -> lifeCycle.endClientRequest());
            String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
            if (StringUtils.isNotEmpty(setCookie) && originalResponse != null) {
                originalResponse.setHeader(HttpHeaders.SET_COOKIE, setCookie);
            }
            if (response.getStatus() != HttpStatus.OK.value()) {
                return proxiedResourceNotAvailable(path, originalRequest);
            }
            return response.asString();
        } catch (ServletException e) {
            log.error("GET request to {} failed", path, e);
            throw new RuntimeException(e);
        }
    }

    public void proxyRequest(final HttpServletRequest req, final HttpServletResponse resp) throws IOException, ServletException {
        final AsyncContext asyncContext = req.startAsync();
        //таймаутом управляют сами proxyRequestExecutor
        asyncContext.setTimeout(0L);
        ProxiedRequestLifecycle lifeCycle = new ProxiedRequestLifecycle(req, metrics).receivedRequest();


        proxyRequestExecutor.forRequest(req.getServletPath())
                .execute(req, resp, asyncContext, lifeCycle, lifeCycle::endClientRequest);
    }

    private String proxiedResourceNotAvailable(String path, HttpServletRequest originalRequest) throws Exception {
        try {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(originalRequest));
            View view = viewResolver.resolveViewName(configuration.isMaintenanceMode() ? MAINTENANCE_VIEW_NAME : NOTAVAILABLE_VIEW_NAME, originalRequest.getLocale());
            PrintToStringHttpServletResponse response = new PrintToStringHttpServletResponse();
            view.render(new HashMap<>(), originalRequest, response);
            return response.asString();
        } catch (RuntimeException re) {
            log.error("Failed to execute fallbackMethod", re);
            return re.getMessage();
        }
    }

}
