package com.otr.portal.proxy.metrics;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author pobedenniy.alexey
 * @since 01.02.18
 */
@Component
@Primary
public class CompositeBackendsMetrics implements BackendsMetrics {
    private List<BackendsMetrics> metrics;

    public CompositeBackendsMetrics(
            MicrometerBackendsMetricsImpl dropwizardBackendsMetrics,
            PrometheusBackendsMetricsImpl prometheusBackendsMetrics,
            RequestHistoryBackendsMetrics requestHistoryBackendsMetrics) {
        this.metrics = Arrays.asList(dropwizardBackendsMetrics, prometheusBackendsMetrics, requestHistoryBackendsMetrics);
    }

    @Override
    public RequestMetric newRequestForBackend(String backendName, String uri) {
        final List<RequestMetric> backendStream = metrics.stream().map(metric -> metric.newRequestForBackend(backendName, uri)).collect(Collectors.toList());
        return new CompositeRequestMetric(backendStream);
    }

    @Override
    public void failRequest(String backedName, String uri) {
        metrics.forEach(m -> m.failRequest(backedName, uri));
    }

    @Override
    public TimerContext startRewrite(String backendName) {
        List<TimerContext> timers = metrics.stream().map(metric -> metric.startRewrite(backendName)).collect(Collectors.toList());
        return new CompositeTimerContext(timers);
    }

    private static class CompositeRequestMetric implements RequestMetric {
        private List<RequestMetric> backendMetrics;

        CompositeRequestMetric(List<RequestMetric> backendMetrics) {
            this.backendMetrics = backendMetrics;
        }

        @Override
        public void end(long responseSize, int status) {
            backendMetrics.forEach(m -> m.end(responseSize, status));
        }
    }

    private class CompositeTimerContext implements TimerContext {
        private List<TimerContext> timers;

        CompositeTimerContext(List<TimerContext> timers) {
            this.timers = timers;
        }

        @Override
        public void stop() {
            timers.forEach(TimerContext::stop);
        }
    }
}