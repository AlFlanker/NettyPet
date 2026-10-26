package dev.example.jettyclient.proxy;

import java.nio.file.Path;
import java.security.KeyStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Неизменяемая TLS identity одного общего клиента. Пароли копируются при передаче
 * и не доступны через getters/toString. Профиль не является глобальным cache key:
 * приложение само хранит client/engine на профиль и включает версию конфигурации
 * в его key при ротации ключей или trust store.
 */
public final class ProxyTlsProfile {
    private static final ProxyTlsProfile SYSTEM_TRUST = builder("system-default").build();

    private final String key;
    private final String displayName;
    private final Path trustStorePath;
    private final String trustStoreType;
    private final char[] trustStorePassword;
    private final Path keyStorePath;
    private final String keyStoreType;
    private final char[] keyStorePassword;
    private final char[] keyManagerPassword;
    private final String keyAlias;
    private final List<String> protocols;

    private ProxyTlsProfile(Builder builder) {
        key = text(builder.key, "key");
        displayName = builder.displayName == null ? key : text(builder.displayName, "displayName");
        trustStorePath = normalized(builder.trustStorePath);
        trustStoreType = builder.trustStoreType;
        trustStorePassword = copy(builder.trustStorePassword);
        keyStorePath = normalized(builder.keyStorePath);
        keyStoreType = builder.keyStoreType;
        keyStorePassword = copy(builder.keyStorePassword);
        keyManagerPassword = copy(builder.keyManagerPassword);
        keyAlias = builder.keyAlias;
        protocols = List.copyOf(builder.protocols);
        if ((trustStorePath == null) != (trustStorePassword == null))
            throw new IllegalArgumentException("trustStorePath and trustStorePassword must be configured together");
        if ((keyStorePath == null) != (keyStorePassword == null))
            throw new IllegalArgumentException("keyStorePath and keyStorePassword must be configured together");
        if (keyAlias != null && keyStorePath == null)
            throw new IllegalArgumentException("keyAlias requires a key store");
        if (keyManagerPassword != null && keyStorePath == null)
            throw new IllegalArgumentException("keyManagerPassword requires a key store");
    }

    public static ProxyTlsProfile systemTrust() { return SYSTEM_TRUST; }

    public static Builder builder(String key) { return new Builder(key); }

    public String key() { return key; }
    public String displayName() { return displayName; }
    public Path trustStorePath() { return trustStorePath; }
    public String trustStoreType() { return trustStoreType; }
    public Path keyStorePath() { return keyStorePath; }
    public String keyStoreType() { return keyStoreType; }
    public String keyAlias() { return keyAlias; }
    public List<String> protocols() { return protocols; }

    /** Called before start; every client receives its own SSL context and connection pool. */
    SslContextFactory.Client createContext() {
        var context = new ClientContext(trustStorePath == null);
        context.setEndpointIdentificationAlgorithm("HTTPS");
        context.setIncludeProtocols(protocols.toArray(String[]::new));
        if (trustStorePath != null) {
            context.setTrustStorePath(trustStorePath.toString());
            context.setTrustStoreType(trustStoreType);
            context.setTrustStorePassword(new String(trustStorePassword));
        }
        if (keyStorePath != null) {
            context.setKeyStorePath(keyStorePath);
            context.setKeyStoreType(keyStoreType);
            context.setKeyStorePassword(new String(keyStorePassword));
            if (keyManagerPassword != null) context.setKeyManagerPassword(new String(keyManagerPassword));
            if (keyAlias != null) context.setCertAlias(keyAlias);
        }
        return context;
    }

    /** Jetty otherwise treats the identity key store as its default trust store. */
    private static final class ClientContext extends SslContextFactory.Client {
        private final boolean systemTrust;

        ClientContext(boolean systemTrust) {
            super(false);
            this.systemTrust = systemTrust;
        }

        @Override protected KeyStore loadTrustStore(Resource resource) throws Exception {
            // null trust managers let JSSE use its normal system trust configuration.
            // A client identity must not implicitly add trust anchors.
            return systemTrust ? null : super.loadTrustStore(resource);
        }
    }

    @Override public String toString() {
        return "ProxyTlsProfile[key=" + key + ", displayName=" + displayName
                + ", trustStore=" + trustStorePath + ", keyStore=" + keyStorePath
                + ", keyAlias=" + keyAlias + ", protocols=" + protocols + ", credentials=<redacted>]";
    }

    private static Path normalized(Path path) { return path == null ? null : path.toAbsolutePath().normalize(); }

    private static char[] copy(char[] value) { return value == null ? null : value.clone(); }

    private static String text(String value, String name) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(name + " must be nonblank and contain no control characters");
        return value;
    }

    private static String storeType(String value) {
        Objects.requireNonNull(value, "storeType");
        if (value.equalsIgnoreCase("PKCS12")) return "PKCS12";
        if (value.equalsIgnoreCase("JKS")) return "JKS";
        throw new IllegalArgumentException("Store type must be PKCS12 or JKS");
    }

    public static final class Builder {
        private final String key;
        private String displayName;
        private Path trustStorePath;
        private String trustStoreType = "PKCS12";
        private char[] trustStorePassword;
        private Path keyStorePath;
        private String keyStoreType = "PKCS12";
        private char[] keyStorePassword;
        private char[] keyManagerPassword;
        private String keyAlias;
        private List<String> protocols = List.of("TLSv1.3", "TLSv1.2");

        private Builder(String key) { this.key = text(key, "key"); }

        public Builder displayName(String name) { displayName = text(name, "displayName"); return this; }

        /** Filesystem PKCS12 trust store; an empty password is valid, null is not. */
        public Builder trustStore(Path path, char[] password) { return trustStore(path, password, "PKCS12"); }

        public Builder trustStore(Path path, char[] password, String type) {
            Path checkedPath = Objects.requireNonNull(path, "trustStorePath");
            char[] checkedPassword = Objects.requireNonNull(password, "trustStorePassword").clone();
            String checkedType = storeType(type);
            trustStorePath = checkedPath;
            trustStorePassword = checkedPassword;
            trustStoreType = checkedType;
            return this;
        }

        public Builder keyStore(Path path, char[] password) { return keyStore(path, password, "PKCS12"); }

        public Builder keyStore(Path path, char[] password, String type) {
            Path checkedPath = Objects.requireNonNull(path, "keyStorePath");
            char[] checkedPassword = Objects.requireNonNull(password, "keyStorePassword").clone();
            String checkedType = storeType(type);
            keyStorePath = checkedPath;
            keyStorePassword = checkedPassword;
            keyStoreType = checkedType;
            return this;
        }

        /** Optional password for a private key differing from the store password. */
        public Builder keyManagerPassword(char[] password) {
            keyManagerPassword = Objects.requireNonNull(password, "keyManagerPassword").clone();
            return this;
        }

        public Builder keyAlias(String alias) { keyAlias = text(alias, "keyAlias"); return this; }

        public Builder protocols(String... values) {
            Objects.requireNonNull(values, "protocols");
            if (values.length == 0) throw new IllegalArgumentException("At least one TLS protocol is required");
            var selected = new LinkedHashSet<String>();
            for (String value : values) {
                if (!"TLSv1.2".equals(value) && !"TLSv1.3".equals(value))
                    throw new IllegalArgumentException("Protocols must be TLSv1.2 or TLSv1.3");
                selected.add(value);
            }
            protocols = List.copyOf(selected);
            return this;
        }

        public ProxyTlsProfile build() { return new ProxyTlsProfile(this); }

        @Override public String toString() { return "ProxyTlsProfile.Builder[credentials=<redacted>]"; }
    }
}
