package com.otr.portal.proxy.ssl;

import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.io.Resource;

import java.util.List;

import static java.lang.String.format;
import static java.util.stream.Collectors.joining;
import static org.springframework.util.StringUtils.hasText;

/**
 * Держатель конфигурации SSL контекста для конкретного бэкэнда
 *
 * @author alexraspopin
 * @since 20.10.2025
 */

@Data
public class BackendSslContext {
    // mTLS (PKCS#12):
    private Resource trustStorePath;    // truststore.p12 (issuer CA внешней системы)
    private String trustStorePassword;
    private Resource keyStorePath;      // client.p12 (клиентский ключ + сертификат)
    private String keyStorePassword;    // пароль client.p12
    private String keyAlias;            // опционально, если в p12 несколько ключей
    private String displayName;         // для UI "ЛК Март"
    private String technicalName;       // заполняется ключом из yml(!)
    // TLS-профиль
    private List<String> enabledProtocols = List.of("TLSv1.3", "TLSv1.2");

    @Override
    public String toString() {
        return "BackendSslContext{" +
                "trustStorePath=" + trustStorePath +
                ", keyStorePath=" + keyStorePath +
                ", keyAlias='" + keyAlias + '\'' +
                ", displayName='" + displayName + '\'' +
                ", technicalName='" + technicalName + '\'' +
                ", enabledProtocols=" + enabledProtocols.stream()
                    .collect(joining(", ", "[", "]")) +
                format(", trustStorePassword=%s", toSecuredString(trustStorePassword))  +
                format(", keyStorePassword=%s", toSecuredString(keyStorePassword))  +
                '}';
    }

    private @NotNull String toSecuredString(String value) {
        return hasText(value)
                ? "***"
                : "";
    }
}
