package dev.example.jettyclient.proxy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProxyTlsProfileTest {
    @Test
    void profilesAndBuildersNeverIncludePasswordsInTheirText() {
        String trustSecret = "test-trust-secret";
        String keySecret = "test-key-secret";
        String managerSecret = "test-manager-secret";
        var builder = ProxyTlsProfile.builder("partner:v2")
                .displayName("Partner")
                .trustStore(Path.of("trust.p12"), trustSecret.toCharArray())
                .keyStore(Path.of("keys.jks"), keySecret.toCharArray(), "JKS")
                .keyManagerPassword(managerSecret.toCharArray())
                .keyAlias("client");

        for (String text : List.of(builder.toString(), builder.build().toString())) {
            assertFalse(text.contains(trustSecret));
            assertFalse(text.contains(keySecret));
            assertFalse(text.contains(managerSecret));
            assertTrue(text.contains("redacted"));
        }
    }

    @Test
    void aProfileCannotAcquireAnAliasOrPrivateKeyPasswordWithoutAKeyStore() {
        assertThrows(IllegalArgumentException.class, () -> ProxyTlsProfile.builder("alias").keyAlias("client").build());
        assertThrows(IllegalArgumentException.class, () -> ProxyTlsProfile.builder("key")
                .keyManagerPassword(new char[0]).build());
        assertThrows(NullPointerException.class, () -> ProxyTlsProfile.builder("trust")
                .trustStore(Path.of("trust.p12"), null));
        assertThrows(NullPointerException.class, () -> ProxyTlsProfile.builder("key")
                .keyStore(null, new char[0]));
    }

    @Test
    void protocolsAndBuilderStateCannotChangeAnAlreadyBuiltProfile() {
        String[] protocols = {"TLSv1.2"};
        var builder = ProxyTlsProfile.builder("partner:v1").protocols(protocols);
        ProxyTlsProfile profile = builder.build();
        protocols[0] = "TLSv1.3";
        builder.protocols("TLSv1.3");

        assertEquals(List.of("TLSv1.2"), profile.protocols());
        assertThrows(UnsupportedOperationException.class, () -> profile.protocols().add("TLSv1.3"));
        assertThrows(IllegalArgumentException.class, () -> ProxyTlsProfile.builder("bad").protocols("SSLv3"));
        assertThrows(IllegalArgumentException.class, () -> ProxyTlsProfile.builder("empty").protocols());
        assertThrows(IllegalArgumentException.class, () -> ProxyTlsProfile.builder("bad\nkey"));
    }

    @Test
    void invalidStoreUpdateCannotLeaveTheBuilderPartiallyChanged() {
        var builder = ProxyTlsProfile.builder("partner");
        assertThrows(NullPointerException.class, () -> builder.trustStore(Path.of("trust.p12"), null));
        assertThrows(IllegalArgumentException.class, () -> builder.keyStore(Path.of("key.p12"), new char[0], "unsupported"));

        ProxyTlsProfile profile = builder.build();
        assertNull(profile.trustStorePath());
        assertNull(profile.keyStorePath());
    }
}
