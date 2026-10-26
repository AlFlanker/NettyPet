package com.otr.portal.proxy.metrics;

import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.otr.portal.core.metrics.SnapshotDataHolder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * @author pobedenniy.alexey
 * @since 07.05.18
 */
@Component
public class RequestHistoryBackendsMetrics implements BackendsMetrics {
    private final Cache<String, UriMetric> data = Caffeine.newBuilder()
            .maximumSize(200)
            .build();

    @Override
    public RequestMetric newRequestForBackend(String backendName, String uri) {
        final String key = backendName + ":" + StringUtils.substringBefore(uri, ";jsessionid=");
        long startedAt = System.currentTimeMillis();
        return (responseSize, status) -> {
            final long now = System.currentTimeMillis();
            //noinspection ConstantConditions
            data.get(key, k -> new UriMetric()).update(responseSize, status, now - startedAt);
        };
    }

    @Override
    public void failRequest(String backedName, String uri) {
        final String key = backedName + ":" + StringUtils.substringBefore(uri, ";jsessionid=");
        //noinspection ConstantConditions
        data.get(key, k -> new UriMetric()).update(0, HttpStatus.REQUEST_TIMEOUT.value(), 0L);
    }

    public Flux<RequestHistoryView> view() {
        return Flux
                .fromIterable(data.asMap().entrySet())
                .map(entry -> new RequestHistoryView(entry.getKey(), entry.getValue()));
    }

    @Override
    public TimerContext startRewrite(String backendName) {
        return () -> {
        };
    }

    private static class UriMetric {
        private ExponentiallyDecayingReservoir size = new ExponentiallyDecayingReservoir();
        private ExponentiallyDecayingReservoir time = new ExponentiallyDecayingReservoir();
        private volatile long ok = 0L;
        private volatile long serverError = 0L;
        private volatile long clientError = 0L;

        public void update(long responseSize, int status, long tookTime) {
            size.update(responseSize);
            time.update(tookTime);
            if (status >= 400 && status < 500) {
                clientError++;
            } else if (status >= 500) {
                serverError++;
            } else {
                ok++;
            }
        }
    }

    public class RequestHistoryView {
        private final String backend;
        private final String uri;
        private final long ok;
        private final long clientErrors;
        private final long serverErrors;
        private final SnapshotDataHolder size;
        private final SnapshotDataHolder time;

        public RequestHistoryView(String key, UriMetric value) {
            this.backend = StringUtils.substringBefore(key, ":");
            this.uri = StringUtils.substringAfter(key, ":");
            this.clientErrors = value.clientError;
            this.serverErrors = value.serverError;
            this.size = new SnapshotDataHolder(value.size.getSnapshot());
            this.time = new SnapshotDataHolder(value.time.getSnapshot());
            this.ok = value.ok;
        }

        public String getBackend() {
            return backend;
        }

        public String getUri() {
            return uri;
        }

        public long getClientErrors() {
            return clientErrors;
        }

        public long getServerErrors() {
            return serverErrors;
        }

        public SnapshotDataHolder getSize() {
            return size;
        }

        public SnapshotDataHolder getTime() {
            return time;
        }

        public long getOk() {
            return ok;
        }
    }
}
