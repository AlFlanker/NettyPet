package dev.example.jettyclient.proxy;

import dev.example.jettyclient.proxy.policies.HeaderRewriteCustomizer;
import dev.example.jettyclient.proxy.policies.LinkRewriteSettings;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.http.HttpFields;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class HeaderRewriteCustomizerTest {
    private static final LinkRewriteSettings SETTINGS = new LinkRewriteSettings(URI.create("http://backend.example/internal"),
            URI.create("https://portal.example/apps/service"), 1024, 2048);
    private static final ProxyRequestMetadata REQUEST = new ProxyRequestMetadata("GET", SETTINGS.backendBase(), Map.of());

    @Test
    void rewritesOriginAndRefererUsingOnlyPreparedValuesAndExplicitlyRequestsIdentity() {
        ProxyHeaderEditor headers = new ProxyHeaderEditor(HttpFields.build()
                .add("Origin", "https://portal.example")
                .add("Referer", "https://portal.example/apps/service/a%20b?x=%2F&x=2")
                .add("Accept-Encoding", "gzip, br").add("Cookie", "session=opaque"));
        customizer(true).customizeRequest(REQUEST, headers);
        assertEquals(List.of("http://backend.example"), headers.values("Origin"));
        assertEquals(List.of("http://backend.example/internal/a%20b?x=%2F&x=2"), headers.values("Referer"));
        assertEquals(List.of("identity"), headers.values("Accept-Encoding"));
        assertEquals(List.of("session=opaque"), headers.values("Cookie"));
    }

    @Test
    void preservesForeignOriginsAndSiblingPathPrefixesAndEncodingWhenRewriteIsDisabled() {
        ProxyHeaderEditor headers = new ProxyHeaderEditor(HttpFields.build()
                .add("Origin", "https://other.example")
                .add("Referer", "https://portal.example/apps/service-other/a")
                .add("Accept-Encoding", "gzip"));
        customizer(false).customizeRequest(REQUEST, headers);
        assertEquals(List.of("https://other.example"), headers.values("Origin"));
        assertEquals(List.of("https://portal.example/apps/service-other/a"), headers.values("Referer"));
        assertEquals(List.of("gzip"), headers.values("Accept-Encoding"));
    }

    @Test
    void mapsAbsoluteRootRelativeAndSchemeRelativeLocationsWithoutReencodingRawComponents() {
        ProxyHeaderEditor headers = new ProxyHeaderEditor(HttpFields.build()
                .add("Location", "http://backend.example/internal/login?x=%2F#part")
                .add("Location", "/internal/other?q=%252F")
                .add("Location", "//backend.example/internal/scheme")
                .add("Set-Cookie", "first=1; Path=/").add("Set-Cookie", "second=2; Path=/"));
        customizer(true).customizeResponse(REQUEST, 302, headers);
        assertEquals(List.of("https://portal.example/apps/service/login?x=%2F#part",
                "https://portal.example/apps/service/other?q=%252F", "https://portal.example/apps/service/scheme"),
                headers.values("Location"));
        assertEquals(List.of("first=1; Path=/", "second=2; Path=/"), headers.values("Set-Cookie"));
    }

    @Test
    void ordinaryRelativeAndForeignLocationsRemainUnchanged() {
        ProxyHeaderEditor headers = new ProxyHeaderEditor(HttpFields.build()
                .add("Location", "../login?next=%2F").add("Location", "https://external.example/a")
                .add("Location", "http://backend.example/internal-other/a"));
        customizer(true).customizeResponse(REQUEST, 302, headers);
        assertEquals(List.of("../login?next=%2F", "https://external.example/a", "http://backend.example/internal-other/a"),
                headers.values("Location"));
    }

    @Test
    void partialRefererUsesCapturedFrontendRequestContextAndNullOriginIsPreserved() {
        ProxyHeaderEditor headers = new ProxyHeaderEditor(HttpFields.build().add("Origin", "null").add("Referer", "../other"));
        customizer(true).customizeRequest(REQUEST, headers);
        assertEquals(List.of("null"), headers.values("Origin"));
        assertEquals(List.of("http://backend.example/internal/other"), headers.values("Referer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip, identity;q=0", "gzip, *;q=0", "GZIP, IDENTITY; Q=0.000",
            "identity;q=0, *;q=1", "identity;q=bogus"})
    void preservesTheClientsCodingConstraintInsteadOfForcingIdentity(String preferences) {
        ProxyRequestMetadata request = new ProxyRequestMetadata("GET", SETTINGS.backendBase(),
                Map.of("Accept-Encoding", List.of(preferences)));
        ProxyHeaderEditor headers = new ProxyHeaderEditor(HttpFields.build().add("Accept-Encoding", preferences));
        customizer(true).customizeRequest(request, headers);
        assertEquals(List.of(preferences), headers.values("Accept-Encoding"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "br;q=0, gzip", "*;q=0, identity;q=0.5", "*;q=0, identity"})
    void requestsIdentityWhenTheClientPermitsIt(String preferences) {
        ProxyRequestMetadata request = new ProxyRequestMetadata("GET", SETTINGS.backendBase(),
                Map.of("Accept-Encoding", List.of(preferences)));
        ProxyHeaderEditor headers = new ProxyHeaderEditor(HttpFields.build().add("Accept-Encoding", preferences));
        customizer(true).customizeRequest(request, headers);
        assertEquals(List.of("identity"), headers.values("Accept-Encoding"));
    }

    private static HeaderRewriteCustomizer customizer(boolean enabled) {
        return new HeaderRewriteCustomizer(SETTINGS, URI.create("https://portal.example/apps/service/dir/current"), enabled);
    }
}
