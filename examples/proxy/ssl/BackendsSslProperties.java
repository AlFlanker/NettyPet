package com.otr.portal.proxy.ssl;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Держатель настроек SSL контекстов
 *
 * @author alexraspopin
 * @since 22.10.2025
 */
@ConfigurationProperties(prefix = "admin.backends.mtls")
public class BackendsSslProperties {
    @Setter @Getter
    private Map<String, BackendSslContext> configs = new HashMap<>();

    public boolean hasSslContext() {
        return !configs.isEmpty();
    }

}