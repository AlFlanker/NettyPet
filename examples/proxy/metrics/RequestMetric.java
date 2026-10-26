package com.otr.portal.proxy.metrics;

/**
 * @author pobedenniy.alexey
 * @since 01.02.18
 */
public interface RequestMetric {
    void end(long responseSize, int status);
}
