package com.otr.portal.proxy.streaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Builder;
import lombok.Value;

import java.util.concurrent.Executor;

/**
 * Параметры создания {@link ServletBodyWriter}.
 */
@Value
@Builder
public class BodyWriterContext {
    HttpServletRequest request;
    HttpServletResponse response;
    BodyWriteListener listener;
    /**
     * Исполнитель сигналов listener'а; для моста Netty → Jetty это event loop канала бэкенда.
     */
    Executor callbackExecutor;
}
