package com.otr.portal.proxy.metrics;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * @author pobedenniy.alexey
 * @since 21.12.17
 */
@Component
public class PrometheusBackendsMetricsImpl implements BackendsMetrics {
    public static final PrometheusTimerContext TIMER_CONTEXT = new PrometheusTimerContext();
    private final Gauge backendActiveRequests;
    private final Counter backendRequests;
    private final Counter failedBackendRequests;
    private final Counter backendRequestsStatusCode;
    private final Histogram backendRequestsTime;
    private final Histogram backendRequestsSize;

    public PrometheusBackendsMetricsImpl(PrometheusMeterRegistry meterRegistry) {
        backendActiveRequests = Gauge
                .builder().name("backend_active_requests").help("Active requests to backend").labelNames("backend").register(meterRegistry.getPrometheusRegistry());

        backendRequests = Counter
                .builder().name("backend_requests").help("Total number parseProxied requests to backend").labelNames("backend").register(meterRegistry.getPrometheusRegistry());

        failedBackendRequests = Counter
                .builder().name("backend_failed_requests").help("Total number parseProxied failed requests to backend").labelNames("backend").register(meterRegistry.getPrometheusRegistry());

        backendRequestsStatusCode = Counter
                .builder().name("backend_responses_code").help("Reponse code counter for backends").labelNames("backend", "status").register(meterRegistry.getPrometheusRegistry());

        backendRequestsTime = Histogram
                .builder().classicUpperBounds(.01, .05, 0.1, .25, .5, 1, 2, 3, 5, 15, 60).name("backend_requests_time").help("Measures backend request processing time in ms").labelNames("backend").register(meterRegistry.getPrometheusRegistry());

        backendRequestsSize = Histogram
                .builder().classicUpperBounds(100, 1000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000).name("backend_requests_size").help("Measures backend request size in bytes").labelNames("backend").register(meterRegistry.getPrometheusRegistry());

    }

    @Override
    public RequestMetric newRequestForBackend(String backendName, String uri) {
        backendActiveRequests.labelValues(backendName).inc();
        backendRequests.labelValues(backendName).inc();
        return new PrometheusRequestMetric(backendName);
    }

    @Override
    public void failRequest(String backedName, String uri) {
        if (backedName != null) {
            failedBackendRequests.labelValues(backedName).inc();
            backendActiveRequests.labelValues(backedName).dec();
        }
    }

    @Override
    public TimerContext startRewrite(String backendName) {
        return TIMER_CONTEXT;
    }

    private void endResponse(String backendName, long responseSize, int status, long elapsedTime) {
        backendActiveRequests.labelValues(backendName).dec();
        final long convert = TimeUnit.MILLISECONDS.convert(elapsedTime, TimeUnit.NANOSECONDS);
        backendRequestsTime.labelValues(backendName).observe(convert / 1000.0);
        backendRequestsStatusCode.labelValues(backendName, String.valueOf(status)).inc();
        backendRequestsSize.labelValues(backendName).observe(responseSize);
    }

    public class PrometheusRequestMetric implements RequestMetric {
        private final long startTime;
        private final String backendName;

        public PrometheusRequestMetric(String backendName) {
            this.backendName = backendName;
            this.startTime = System.currentTimeMillis();
        }

        @Override
        public void end(long responseSize, int status) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            endResponse(backendName, responseSize, status, elapsedTime);
        }
    }

    public static class PrometheusTimerContext implements TimerContext {
        @Override
        public void stop() {
        }
    }
}
