package com.otr.portal.proxy.ssl;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Конфиг для сборки SSL контекстов бэкэндов из .yml
 *
 * @author alexraspopin
 * @since 22.10.2025
 */
@Configuration
@EnableConfigurationProperties(BackendsSslProperties.class)
class BackendSslContextConfig {
}
