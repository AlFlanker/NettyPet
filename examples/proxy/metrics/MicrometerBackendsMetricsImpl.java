package com.otr.portal.proxy.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static io.micrometer.core.instrument.Timer.*;

/**
 * @author pobedenniy.alexey
 * @since 21.12.17
 */
@Component
public class MicrometerBackendsMetricsImpl implements BackendsMetrics {
    private static final Logger log = LoggerFactory.getLogger(MicrometerBackendsMetricsImpl.class);
    public static final String COMMON_PREFIX = "proxy.";
    public static final String RESPONSE_REWRITE_TIME_METRIC_NAME = COMMON_PREFIX + "response-rewrite";
    public static final String ERR_REQUESTS_METRIC_NAME = COMMON_PREFIX + "err-requests";
    public static final String ACTIVE_REQUESTS_METRIC_NAME = COMMON_PREFIX + "active-requests";
    public static final String REQUESTS_METRIC_NAME = COMMON_PREFIX + "requests";
    public static final String RESPONSE_TIME_METRIC_NAME = COMMON_PREFIX + "response-time";
    public static final String RESPONSE_SIZE_METRIC_NAME = COMMON_PREFIX + "response-size";
    public static final String RESPONSES_METRIC_NAME = COMMON_PREFIX + "responses";
    public static final String RESPONSE_CODE_METRIC_NAME = COMMON_PREFIX + "response-code";
    public static final String BACKEND_TAG = "backend";
    public static final String RESPONSE_CODE_TAG_NAME = "code";

    private MeterRegistry metricRegistry;
    private Map<String, AtomicLong> activeGaugesAtomic = new ConcurrentHashMap<>(20);
    private Map<String, Gauge> activeGauges = new ConcurrentHashMap<>(20);

    public MicrometerBackendsMetricsImpl(MeterRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    @Override
    public RequestMetric newRequestForBackend(String backendName, String uri) {
        long counter = activeGauge(backendName).incrementAndGet();

        log.debug("Nb parseProxied active requests to {} {}", backendName, counter);
        metricRegistry.counter(REQUESTS_METRIC_NAME, BACKEND_TAG, backendName).increment();
        /*requestCounters.computeIfAbsent(backendName, key -> Counter
                .builder(REQUESTS_METRIC_NAME)
                .tag(BACKEND_TAG, key)
                .register(metricRegistry))
                .increment();*/
        return new DropwizardRequestMetric(
                backendName,
                start(metricRegistry),
                metricRegistry.timer(RESPONSE_TIME_METRIC_NAME, BACKEND_TAG, backendName));
    }

    @Override
    public void failRequest(String backedName, String uri) {
        decreaseActiveRequests(backedName);
        metricRegistry.counter(ERR_REQUESTS_METRIC_NAME, BACKEND_TAG, backedName).increment();
    }

    @Override
    public TimerContext startRewrite(String backendName) {
        Sample timer = start(metricRegistry);
        return new MicrometerTimerContext(timer,
                metricRegistry.timer(RESPONSE_REWRITE_TIME_METRIC_NAME, BACKEND_TAG, backendName));
    }

    private AtomicLong activeGauge(String backendName) {
        AtomicLong gauge = activeGaugesAtomic.computeIfAbsent(backendName, name -> new AtomicLong(0L));
        activeGauges
                .computeIfAbsent(backendName, name -> Gauge.builder(ACTIVE_REQUESTS_METRIC_NAME, gauge::get).tag(BACKEND_TAG, name).register(metricRegistry));
        return gauge;

    }

    private void endResponse(String backendName, long responseSize, int status) {
        metricRegistry.summary(RESPONSE_SIZE_METRIC_NAME, BACKEND_TAG, backendName).record(responseSize);
        metricRegistry.counter(RESPONSES_METRIC_NAME, BACKEND_TAG, backendName).increment();
        metricRegistry.counter(RESPONSE_CODE_METRIC_NAME, BACKEND_TAG, backendName).increment();
        decreaseActiveRequests(backendName);
    }

    private void decreaseActiveRequests(String backedName) {
        final AtomicLong activeGague = activeGauge(backedName);
        long activeRequestCounter = activeGague.decrementAndGet();
        //в некоторых случаях этот метод может срабатывать несколько раз. Сперва Hystrix его вызове при таймауте запроса, затем он вызывается при нормальном завершении
        if (activeRequestCounter < 0) {
            activeGague.compareAndSet(activeRequestCounter, 0L);
        }
    }

    public HistogramSnapshot getSizeHistogram(String backendName) {
        return metricRegistry.summary(RESPONSE_SIZE_METRIC_NAME, BACKEND_TAG, backendName).takeSnapshot();
    }

    public HistogramSnapshot getTimeHistogram(String backendName) {
        return metricRegistry.timer(RESPONSE_TIME_METRIC_NAME, BACKEND_TAG, backendName).takeSnapshot();
    }

    private class DropwizardRequestMetric implements RequestMetric {
        private final String backendName;
        private final Sample sample;
        private final Timer timer;

        DropwizardRequestMetric(String backendName, Sample sample, Timer timer) {
            this.backendName = backendName;
            this.sample = sample;
            this.timer = timer;
        }

        @Override
        public void end(long responseSize, int status) {
            sample.stop(timer);
            endResponse(backendName, responseSize, status);
        }
    }

    private class MicrometerTimerContext implements TimerContext {
        private final Sample time;
        private final Timer timer;

        MicrometerTimerContext(Sample time, Timer timer) {
            this.time = time;
            this.timer = timer;
        }

        @Override
        public void stop() {
            time.stop(timer);
        }
    }
}
