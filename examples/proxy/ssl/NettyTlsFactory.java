package com.otr.portal.proxy.ssl;

import com.otr.portal.proxy.BackendInfo;
import com.otr.portal.proxy.exceptions.BackendSslContextException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.util.CollectionUtils.isEmpty;
import static org.springframework.util.StringUtils.hasText;


/**
 * Создает и кэширует SSL-контексты Netty для безопасного подключения к бэкэндам
 *
 * @author alexraspopin
 * @since 20.10.2025
 */
@Component
@RequiredArgsConstructor
public class NettyTlsFactory {
    public static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    private final BackendsSslContextProvider sslContextResolver;
    private final Map<String, SslContext> cache = new ConcurrentHashMap<>();

    public Optional<SslContext> forBackend(BackendInfo backendInfo) {
        return this.sslContextResolver.provide(backendInfo)
                .map(sslContext ->
                        cache.computeIfAbsent(backendInfo.name(),
                                name -> build(backendInfo, sslContext)));

    }


    private SslContext build(BackendInfo b, BackendSslContext cfg) {
        try {
            // TRUST (кого считаем доверенным сервером)
            TrustManagerFactory tmf = null;
            if (cfg.getTrustStorePath() != null) {
                KeyStore trustKs = loadPkcs12(cfg.getTrustStorePath(), cfg.getTrustStorePassword());
                tmf = tmfFromKeyStore(trustKs);
            }

            // CLIENT-AUTH
            PrivateKey clientKey = null;
            X509Certificate[] clientChain = null;
            if (cfg.getKeyStorePath() != null) {
                KeyStore keyKs = loadPkcs12(cfg.getKeyStorePath(), cfg.getKeyStorePassword());

                // Если truststore не задан, но в этом же p12 есть trustedCertEntry - ипользуем его как trust
                if (tmf == null && containsTrustedCertEntry(keyKs)) {
                    tmf = tmfFromKeyStore(keyKs);
                } else if (tmf == null) {
                    throw new BackendSslContextException("Отсутствуют доверенные материалы: не указан trustStore и не найдены trustedCertEntry в keyStore");
                }

                String alias = chooseClientAlias(keyKs, cfg.getKeyAlias());
                clientKey = (PrivateKey) keyKs.getKey(alias, toChars(cfg.getKeyStorePassword()));
                clientChain = asX509Chain(keyKs.getCertificateChain(alias));
                if (clientKey == null || clientChain.length == 0) {
                    throw new BackendSslContextException("Алиас '" + alias + "' не содержит ключа или цепочки сертификатов в " + cfg.getKeyStorePath());
                }
            }

            // Сборка SslContext
            SslContextBuilder builder = SslContextBuilder.forClient().sslProvider(SslProvider.JDK);
            if (tmf != null) {
                builder.trustManager(tmf);
            }
            if (clientKey != null) {
                builder.keyManager(clientKey, clientChain);
            }

            // Протоколы
            if (!isEmpty(cfg.getEnabledProtocols())) {
                builder.protocols(cfg.getEnabledProtocols()
                        .toArray(String[]::new));
            }
            // Шифры только для TLS 1.2. 1.3 управляет JDK!
            if (cfg.getEnabledProtocols().stream().anyMatch("TLSv1.2"::equals)) {
                builder.ciphers(
                        Arrays.asList(
                                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
                        ),
                        io.netty.handler.ssl.SupportedCipherSuiteFilter.INSTANCE
                );
            }
            return builder.build();

        } catch (Exception e) {
            throw new BackendSslContextException("Не удалось построить SslContext для бэкэнда " + b.name(), e);
        }
    }

    private KeyStore loadPkcs12(Resource res, String storePass) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = res.getInputStream()) {
            ks.load(in, toChars(storePass));
        }
        return ks;
    }

    private TrustManagerFactory tmfFromKeyStore(KeyStore ts) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        return tmf;
    }

    private boolean containsTrustedCertEntry(KeyStore ks) throws Exception {
        var en = ks.aliases();
        while (en.hasMoreElements()) {
            String a = en.nextElement();
            if (ks.isCertificateEntry(a)) {
                return true; // trustedCertEntry найден
            }
        }
        return false;
    }

    private String chooseClientAlias(KeyStore ks, String preferredAlias) throws Exception {
        if (hasText(preferredAlias)) {
            if (ks.isKeyEntry(preferredAlias)) {
                return preferredAlias;
            }
            throw new BackendSslContextException("Предпочитаемый алиас '" + preferredAlias + "' не найден как ключевая запись");
        }
        // берём первый PrivateKeyEntry
        var en = ks.aliases();
        // необходимо взять первую запись с приватным ключом!!
        while (en.hasMoreElements()) {
            String a = en.nextElement();
            if (ks.isKeyEntry(a)) {
                return a;
            }
        }

        throw new BackendSslContextException("В предоставленном keyStore отсутствуют PrivateKeyEntry");
    }

    private X509Certificate[] asX509Chain(java.security.cert.Certificate[] chain) {
        if (chain == null) {
            return new X509Certificate[0];
        }
        X509Certificate[] out = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            out[i] = (X509Certificate) chain[i];
        }
        return out;
    }

    private char[] toChars(String s) {
        return s == null
                ? new char[0]
                : s.toCharArray();
    }
}