package com.otr.portal.proxy;

import com.otr.portal.proxy.logging.ProxiedRequestLifecycle;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * @author pobedenniy.alexey
 * @since 12.10.16
 */
public interface ProxyRequestExecutor {
    void execute(HttpServletRequest req, HttpServletResponse resp, AsyncContext asyncContext, ProxiedRequestLifecycle lifeCycle, RequestCallback callback) throws IOException, ServletException;
    void execute(String uri, HttpServletRequest req, PrintToStringHttpServletResponse response, ProxiedRequestLifecycle lifeCycle, RequestCallback callback) throws IOException, ServletException;
}
