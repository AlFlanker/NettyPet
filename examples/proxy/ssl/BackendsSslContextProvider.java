package com.otr.portal.proxy.ssl;

import com.otr.portal.hosts.Host;
import com.otr.portal.hosts.HostsConfiguration;
import com.otr.portal.metadata.ServerMetadataExtensionService;
import com.otr.portal.metadata.model.SelectMetadataField;
import com.otr.portal.proxy.BackendInfo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Optional.ofNullable;
import static org.springframework.util.StringUtils.hasText;

/**
 * Провайдер SSL контекстов
 *
 * @author alexraspopin
 * @since 22.10.2025
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackendsSslContextProvider {
    public static final String BACKEND_SSL_CONTEXT = "SSL_CONTEXT";

    private final ServerMetadataExtensionService metadataExtensionService;
    private final BackendsSslProperties sslProperties;

    /**
     * Ищет по ключу(идентификатору) необходимых SSL контекст
     * @see BackendSslContext
     * @param backend - целевая система
     * @return - SSL контекст
     */
    Optional<BackendSslContext> provide(@NotNull BackendInfo backend) {
        return ofNullable(backend.getSslContextKey())
                .map(sslKey -> this.sslProperties.getConfigs().get(sslKey));
    }

    /**
     * Данный метод формирует список контекстов для настройки TLS бэкэнда
     */
    @PostConstruct
    void setUp() {
        // Указываем тех.имена
        this.sslProperties.getConfigs()
                .forEach((key, backendSslContext) ->
                        backendSslContext.setTechnicalName(key));

        if (this.sslProperties.hasSslContext()) {
            log.warn("Конфигурация mTLS контекста для бэкэндов загружена: {}", sslProperties.getConfigs());
            enrichBackendPropertiesWithSslContexts();
        }

    }

    private void enrichBackendPropertiesWithSslContexts() {
        String defaultValue = "Не использовать SSL(mTLS)";
        final var selectMetadataField = new SelectMetadataField(
                BackendsSslContextProvider.BACKEND_SSL_CONTEXT,
                defaultValue,
                "mTLS контекст",
                "Определяет SSL контекст для запросов к бэкэндам. Для добавление новой конфигурации обратить к администратору",
                "",
                "Изменение значения параметра требует перезагрузки сервера!!!"
        );
        this.metadataExtensionService.registerExtensionForObject(
                HostsConfiguration.class,
                Host.class,
                selectMetadataField
        );

        selectMetadataField.addItem("", defaultValue);
        this.sslProperties.getConfigs().forEach((key, sslContext) ->
                selectMetadataField.addItem(key, getValue(sslContext)));
    }

    private static String getValue(BackendSslContext sslContext) {
        return hasText(sslContext.getDisplayName())
                ? sslContext.getDisplayName()
                : sslContext.getTechnicalName();
    }
}
