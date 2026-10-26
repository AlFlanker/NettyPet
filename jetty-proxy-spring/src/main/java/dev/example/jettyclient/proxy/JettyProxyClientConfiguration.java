package dev.example.jettyclient.proxy;

import org.eclipse.jetty.client.HttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Opt-in Spring wiring, activated with {@code @Import(JettyProxyClientConfiguration.class)}.
 * This class is deliberately not a component-scan candidate. It creates transport
 * beans only: the host owns Filter/Servlet registration, URLs and server settings.
 * All internal dependencies are qualified; an unrelated host HttpClient is never selected.
 */
@EnableConfigurationProperties(JettyProxySpringProperties.class)
public final class JettyProxyClientConfiguration {
    public static final String PROPERTIES_BEAN_NAME = "jettyProxyProperties";
    public static final String HTTP_CLIENT_BEAN_NAME = "jettyProxyHttpClient";
    public static final String ENGINE_BEAN_NAME = "jettyProxyEngine";
    public static final String FETCH_CLIENT_BEAN_NAME = "jettyProxyFetchClient";
    public static final String TLS_PROFILE_BEAN_NAME = "jettyProxyTlsProfile";

    @Bean(name = PROPERTIES_BEAN_NAME)
    public JettyProxyProperties jettyProxyProperties(JettyProxySpringProperties bound) {
        return bound.toCoreProperties();
    }

    @Bean(name = HTTP_CLIENT_BEAN_NAME, destroyMethod = "stop")
    public HttpClient jettyHttpClient(@Qualifier(PROPERTIES_BEAN_NAME) JettyProxyProperties properties,
            @Qualifier(TLS_PROFILE_BEAN_NAME) ObjectProvider<ProxyTlsProfile> profiles)
            throws Exception {
        return JettyProxyClientFactory.createStarted(properties, profiles.getIfAvailable(ProxyTlsProfile::systemTrust));
    }

    /** Spring closes both users before stopping their shared, owned HttpClient. */
    @Bean(name = ENGINE_BEAN_NAME, destroyMethod = "close")
    public JettyProxyEngine jettyProxyEngine(@Qualifier(HTTP_CLIENT_BEAN_NAME) HttpClient client,
            @Qualifier(PROPERTIES_BEAN_NAME) JettyProxyProperties properties,
            ObjectProvider<ProxyHeaderCustomizer> customizers) {
        return new JettyProxyEngine(client, properties, customizers.orderedStream().toList());
    }

    @Bean(name = FETCH_CLIENT_BEAN_NAME, destroyMethod = "close")
    public ProxyFetchClient jettyProxyFetchClient(@Qualifier(HTTP_CLIENT_BEAN_NAME) HttpClient client,
            @Qualifier(PROPERTIES_BEAN_NAME) JettyProxyProperties properties, JettyProxySpringProperties bound) {
        ProxyFetchOptions configured = bound.toFetchOptions();
        ProxyFetchOptions capacity = new ProxyFetchOptions(Math.min(configured.maxConcurrent(), properties.maxConcurrent()),
                configured.decoderThreads(), Math.min(configured.maxQueued(), properties.maxConcurrent()));
        return new ProxyFetchClient(client, properties, capacity);
    }
}
