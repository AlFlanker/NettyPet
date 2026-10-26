package com.otr.portal.proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Optional;

/**
 * Оборачивает запрос, убирая внешний префикс {@code /etd} сразу после {@link #getContextPath()} в URI и в {@link HttpHeaders#REFERER}.
 */
final class EtdExternalPathServletRequest extends HttpServletRequestWrapper {

    private final String strippedRequestUri;
    private final String strippedServletPath;
    private final String strippedPathInfo;
    private final Optional<String> refererOverride;

    EtdExternalPathServletRequest(HttpServletRequest delegate) {
        super(delegate);
        String contextPath = delegate.getContextPath();
        String requestUri = delegate.getRequestURI();
        String afterContext = requestUri.substring(contextPath.length());
        String strippedAfter = stripEtdSegmentPrefix(afterContext);
        this.strippedRequestUri = contextPath + strippedAfter;
        String origServletPath = delegate.getServletPath();
        String origPathInfo = delegate.getPathInfo();
        if (origPathInfo == null) {
            this.strippedServletPath = strippedAfter;
            this.strippedPathInfo = null;
        } else if (strippedAfter.startsWith(origServletPath)) {
            this.strippedServletPath = origServletPath;
            String pi = strippedAfter.substring(origServletPath.length());
            this.strippedPathInfo = pi.isEmpty() ? null : pi;
        } else {
            this.strippedServletPath = strippedAfter;
            this.strippedPathInfo = null;
        }
        String origReferer = delegate.getHeader(HttpHeaders.REFERER);
        String strippedReferer = stripEtdFromRefererUrl(origReferer, contextPath, delegate);
        this.refererOverride = StringUtils.equals(origReferer, strippedReferer)
                ? Optional.empty()
                : Optional.of(strippedReferer);
    }

    static Optional<String> stripEtdFromRequestUri(String contextPath, String requestUri) {
        if (!requestUri.startsWith(contextPath)) {
            return Optional.empty();
        }
        String afterContext = requestUri.substring(contextPath.length());
        String stripped = stripEtdSegmentPrefix(afterContext);
        if (stripped.equals(afterContext)) {
            return Optional.empty();
        }
        return Optional.of(contextPath + stripped);
    }

    /**
     * Удаляет ведущий сегмент {@code /etd} у пути внутри приложения.
     */
    static String stripEtdSegmentPrefix(String pathWithinApplication) {
        if (StringUtils.equals(pathWithinApplication, "/etd")) {
            return "/";
        }
        if (pathWithinApplication.startsWith("/etd/")) {
            return pathWithinApplication.substring("/etd".length());
        }
        return pathWithinApplication;
    }

    /**
     * Снимает сегмент {@code /etd} из пути абсолютного URL того же origin, что и {@code request}.
     */
    static String stripEtdFromRefererUrl(String referer, String contextPath, HttpServletRequest request) {
        if (StringUtils.isBlank(referer)) {
            return referer;
        }
        try {
            URI uri = URI.create(referer.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return referer;
            }
            if (!sameOrigin(uri, request)) {
                return referer;
            }
            String path = uri.getRawPath();
            if (StringUtils.isEmpty(path)) {
                return referer;
            }
            String cp = StringUtils.defaultString(contextPath, "");
            if (!path.startsWith(cp)) {
                return referer;
            }
            String afterContext = path.substring(cp.length());
            if (afterContext.isEmpty()) {
                afterContext = "/";
            }
            if (!afterContext.startsWith("/")) {
                afterContext = "/" + afterContext;
            }
            String stripped = stripEtdSegmentPrefix(afterContext);
            if (stripped.equals(afterContext)) {
                return referer;
            }
            String newPath = cp + stripped;
            return rebuildAbsoluteUri(uri, newPath);
        } catch (IllegalArgumentException ex) {
            return referer;
        }
    }

    private static boolean sameOrigin(URI refUri, HttpServletRequest request) {
        if (!StringUtils.equalsIgnoreCase(refUri.getScheme(), request.getScheme())) {
            return false;
        }
        if (!StringUtils.equalsIgnoreCase(refUri.getHost(), request.getServerName())) {
            return false;
        }
        int refPort = refUri.getPort();
        if (refPort < 0) {
            refPort = "https".equalsIgnoreCase(refUri.getScheme()) ? 443 : 80;
        }
        return refPort == request.getServerPort();
    }

    private static String rebuildAbsoluteUri(URI uri, String newPath) {
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getRawAuthority()).append(newPath);
        if (StringUtils.isNotEmpty(uri.getRawQuery())) {
            sb.append('?').append(uri.getRawQuery());
        }
        if (StringUtils.isNotEmpty(uri.getRawFragment())) {
            sb.append('#').append(uri.getRawFragment());
        }
        return sb.toString();
    }

    @Override
    public String getRequestURI() {
        return strippedRequestUri;
    }

    @Override
    public StringBuffer getRequestURL() {
        StringBuffer url = new StringBuffer();
        String scheme = getScheme();
        url.append(scheme).append("://").append(getServerName());
        int port = getServerPort();
        if ("http".equals(scheme) && port != 80 || "https".equals(scheme) && port != 443) {
            url.append(':').append(port);
        }
        url.append(strippedRequestUri);
        return url;
    }

    @Override
    public String getServletPath() {
        return strippedServletPath;
    }

    @Override
    public String getPathInfo() {
        return strippedPathInfo;
    }

    @Override
    public String getHeader(String name) {
        if (name != null && HttpHeaders.REFERER.equalsIgnoreCase(name) && refererOverride.isPresent()) {
            return refererOverride.get();
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (name != null && HttpHeaders.REFERER.equalsIgnoreCase(name) && refererOverride.isPresent()) {
            return Collections.enumeration(Collections.singletonList(refererOverride.get()));
        }
        return super.getHeaders(name);
    }
}
