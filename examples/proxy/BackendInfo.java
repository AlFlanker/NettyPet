package com.otr.portal.proxy;

import com.otr.portal.hosts.Backend;

import static com.otr.portal.backends.ssl.BackendSslContextMetadataExtension.BACKEND_SSL_CONTEXT;

/**
 * Метаданные бекенда
 *
 * @author alexraspopin
 * @since 11.03.2026
 */
public class BackendInfo {
    private final String name;
    private final String fullBackendUrl;
    private final int timeout;
    private final int poolSize;
    private final String sslContextKey;

    BackendInfo(String name, String fullBackendUrl, int timeout, int poolSize, String sslContextKey) {
        this.name = name;
        this.fullBackendUrl = fullBackendUrl;
        this.timeout = timeout;
        this.poolSize = poolSize;
        this.sslContextKey = sslContextKey;
    }

    public static BackendInfo fromBackend(Backend backend) {
        return new BackendInfo(
                backend.name(),
                backend.fullBackendUrl("/"),
                backend.getTimeout(),
                backend.getPoolSize(),
                backend.getExtFieldValue(BACKEND_SSL_CONTEXT));
    }

    public String name() {
        return name;
    }

    public String fullBackendUrl() {
        return fullBackendUrl;
    }

    public int timeout() {
        return timeout;
    }

    public int poolSize() {
        return poolSize;
    }

    public String getSslContextKey() {
        return sslContextKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        BackendInfo that = (BackendInfo) o;

        if (timeout != that.timeout) {
            return false;
        }
        if (poolSize != that.poolSize) {
            return false;
        }
        if (!name.equals(that.name)) {
            return false;
        }
        return fullBackendUrl.equals(that.fullBackendUrl);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + fullBackendUrl.hashCode();
        result = 31 * result + timeout;
        result = 31 * result + poolSize;
        return result;
    }
}
