package com.otr.portal.proxy;

import org.springframework.util.AntPathMatcher;

/**
 * @author pobedenniy.alexey
 * @since 12.10.16
 */
public enum ProxyRequestClassifier {
    ZKAU("proxyZkau"),
    UPLOAD("proxyUpload"),
    STATIC("proxyStatic"),
    OTHER("proxyRequest"),
    COMET("proxyComet"),
    ASYNC("async");

    private String commandKey;
    static AntPathMatcher pathMatcher = new AntPathMatcher();

    ProxyRequestClassifier(String commandKey) {
        this.commandKey = commandKey;
    }

    public String getCommandKey() {
        return commandKey;
    }

    public static ProxyRequestClassifier classify(String servletPath) {
        if (servletPath.endsWith("/zkau")) {
            return ProxyRequestClassifier.ZKAU;
        } else if (servletPath.endsWith("/zkcomet")) {
            return ProxyRequestClassifier.COMET;
        } else if (servletPath.endsWith("/upload")) {
            return ProxyRequestClassifier.UPLOAD;
        } else if (staticResource(servletPath)) {
            return ProxyRequestClassifier.STATIC;
        }
        return OTHER;
    }

    private static boolean staticResource(String servletPath) {
        return pathMatcher.match("/**/*.js", servletPath)
                || pathMatcher.match("/**/*.css", servletPath)
                || pathMatcher.match("/**/*.dsp", servletPath)
                || pathMatcher.match("/**/*.gif", servletPath)
                || pathMatcher.match("/**/*.png", servletPath)
                || pathMatcher.match("/**/*.wcs", servletPath)
                || pathMatcher.match("/**/*.wpd", servletPath);
    }
}
