package com.otr.portal.proxy.logging;

import com.otr.portal.proxy.metrics.BackendsMetrics;
import com.otr.portal.proxy.metrics.RequestMetric;
import com.otr.portal.proxy.metrics.TimerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author pobedenniy.alexey
 * @since 02.07.18
 */
public class ProxiedRequestLifecycle {
    private static final Logger log = LoggerFactory.getLogger("HttpProxy.trace");
    private static final Logger logExchange = LoggerFactory.getLogger("HttpProxy.exchange");

    public static final Integer SLOW_REQUEST_TIME_SEC = Integer.parseInt(System.getProperty("slow-request-time-sec", "170"));

    private final HttpServletRequest req;
    private final StopWatch stopWatch;
    private BackendsMetrics metrics;
    private RequestMetric metricContext;
    private String targetUrl;
    private String backend;
    private TimerContext responseRewriteTimer;

    public void setBackendName(String backend) {
        this.backend = backend;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public ProxiedRequestLifecycle(HttpServletRequest req, BackendsMetrics metrics) {
        this.metrics = metrics;
        this.req = req;
        this.stopWatch = new StopWatch();
    }

    public ProxiedRequestLifecycle receivedRequest() {
        this.stopWatch.start();
        if (logExchange.isTraceEnabled()) {
            logExchange.trace("Proxying {} request to {}", req.getMethod(), req.getRequestURI());
        }
        return this;
    }

    public ProxiedRequestLifecycle endClientRequest() {
        long tookTime = stopWatch.stop();
        if (log.isTraceEnabled()) {
            log.trace("Request to {} finished in {}ms{}", targetUrl, tookTime, stopWatch.toPrintString());
        } else if (tookTime >=  SLOW_REQUEST_TIME_SEC * 1000) {
            log.warn("Request to {} was too slow and finished in {}ms{}", targetUrl, tookTime, stopWatch.toPrintString());
        }
        return this;
    }

    public void loadedFromCache() {
        stopWatch.mileStone("loaded from cache");
    }

    public void obtainedBackendInfoOnRequest(String backend, String targetUri) {
        setBackendName(backend);
        this.metricContext = metrics.newRequestForBackend(backend, targetUri);
        stopWatch.mileStone("obtained backend");
    }

    public void calculatedFullBackendURL(String fullBackendURL) {
        logExchange.debug("Proxying request to {}", fullBackendURL);
        this.targetUrl = fullBackendURL;
        stopWatch.mileStone("calculate url");
    }

    public void connectedToBackend() {
        stopWatch.mileStone("connect to backend");
    }

    public void processedFrontendHeaders() {
        stopWatch.mileStone("process frontend headers");
    }

    public void sentRequestToBackend() {
        logExchange.debug("Sent request to {}", targetUrl);
        stopWatch.mileStone("sent request to backend");
    }

    public void recievedBackendResponse() {
        stopWatch.mileStone("received backend response");
    }

    public void startedContentRewrite() {
        this.responseRewriteTimer = metrics.startRewrite(backend);
        stopWatch.mileStone("started content rewrite");
    }

    public void endContentRewrite() {
        this.responseRewriteTimer.stop();
        stopWatch.mileStone("end content rewrite");
    }

    public void processedBackendHeaders() {
        stopWatch.mileStone("processed backend headers");
    }

    public void processedBackendResponse(long responseSize, int code) {
        stopWatch.mileStone("processed backend response");
        metricContext.end(responseSize, code);
    }

    public void failRequest() {
        metrics.failRequest(backend, targetUrl);
        stopWatch.mileStone("failed request");
        long tookTime = stopWatch.getLastDuration();
        logExchange.error("Request to {} failed after {}ms{}", targetUrl, tookTime, stopWatch.toPrintString());
    }

    private class StopWatch {
        private long startTime = 0L;
        private long lastTime = 0L;
        private long endTime = 0L;
        private Object[] mileStones = new String[15];
        private Long[] mileStonesTimes = new Long[15];
        private int offset = 0;

        public void start() {
            startTime = System.currentTimeMillis();
        }

        public void mileStone(String key) {
            int o = offset;
            mileStones[o] = key;
            final long now = System.currentTimeMillis();
            if (o > 0) {
                mileStonesTimes[o] = now - lastTime;
            } else {
                mileStonesTimes[o] = now - startTime;
            }
            this.lastTime = now;
            this.offset++;
        }

        public long stop() {
            this.endTime = System.currentTimeMillis();
            mileStones[offset] = "total";
            mileStonesTimes[offset++] = System.currentTimeMillis() - startTime;
            return this.endTime - this.startTime;
        }

        public long getLastDuration() {
            return this.lastTime - this.startTime;
        }

        public String toPrintString() {
            StringBuilder sbFormat = new StringBuilder("\n| ");
            for (int i = 0; i < offset; i++) {
                sbFormat.append("%25s| ");
            }
            sbFormat.append("|");
            final String format = sbFormat.toString();
            return String.format(format, mileStones) + String.format(format, (Object[]) mileStonesTimes);
        }
    }
}
