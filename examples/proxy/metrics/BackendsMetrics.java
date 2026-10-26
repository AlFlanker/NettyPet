package com.otr.portal.proxy.metrics;

/**
 * @author pobedenniy.alexey
 * @since 01.02.18
 */
public interface BackendsMetrics {
    RequestMetric newRequestForBackend(String backendName, String uri);

    void failRequest(String backedName, String uri);

    TimerContext startRewrite(String backendName);
}
