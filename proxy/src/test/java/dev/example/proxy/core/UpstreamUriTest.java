package dev.example.proxy.core;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class UpstreamUriTest {
    @Test
    void preservesEscapesRepeatedQueryParametersAndEmptyValues() {
        URI target = UpstreamUri.resolve(URI.create("https://upstream.example/base/"),
                "/a%2Fb/%2520", "a=1&a=2&empty=&space=+&slash=%2F");
        assertEquals("https://upstream.example/base/a%2Fb/%2520?a=1&a=2&empty=&space=+&slash=%2F",
                target.toASCIIString());
        assertEquals("/base/a%2Fb/%2520?a=1&a=2&empty=&space=+&slash=%2F", UpstreamUri.requestTarget(target));
    }

    @Test
    void leadingDoubleSlashCannotChangeTheDestination() {
        URI target = UpstreamUri.resolve(URI.create("http://trusted.example"), "//evil.example/path", null);
        assertEquals("trusted.example", target.getHost());
        assertEquals("//evil.example/path", target.getRawPath());
    }

    @Test
    void distinguishesAnEmptyQueryFromNoQuery() {
        assertEquals("/x?", UpstreamUri.requestTarget(
                UpstreamUri.resolve(URI.create("http://localhost"), "/x", "")));
        assertEquals("/x", UpstreamUri.requestTarget(
                UpstreamUri.resolve(URI.create("http://localhost"), "/x", null)));
    }

    @Test
    void rejectsInvalidDestinationMetadata() {
        for (String value : new String[]{"file:///tmp/file", "http://user:pass@example.com",
                "http://example.com/#fragment", "http://example.com:99999", "http://example.com:0"}) {
            assertThrows(IllegalArgumentException.class, () -> UpstreamUri.validate(URI.create(value)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> UpstreamUri.resolve(URI.create("https://example.com?q=1"), "/x", null));
        assertThrows(IllegalArgumentException.class,
                () -> UpstreamUri.resolve(URI.create("https://example.com"), "/x#f", null));
    }

    @Test
    void normalizesOriginAndBracketsIpv6OnlyInAuthority() {
        Origin origin = Origin.from(URI.create("HTTP://[::1]:8080/x"));
        assertEquals("http", origin.scheme());
        assertEquals("::1", origin.host());
        assertEquals(8080, origin.port());
        assertEquals("[::1]:8080", origin.authority());
        assertEquals("example.com", Origin.from(URI.create("https://EXAMPLE.com:443")).authority());
        assertEquals("/", UpstreamUri.requestTarget(URI.create("http://localhost")));
    }

    @Test
    void encodedControlBytesRemainDataWhileLiteralLineBreaksAreRejected() {
        URI encoded = UpstreamUri.resolve(URI.create("http://trusted.example"),
                "/file%0D%0Aname", "value=%0d%0a&plus=+");
        assertEquals("/file%0D%0Aname?value=%0d%0a&plus=+", UpstreamUri.requestTarget(encoded));
        assertThrows(IllegalArgumentException.class, () -> UpstreamUri.resolve(
                URI.create("http://trusted.example"), "/file\r\nX-Injected: 1", null));
    }
}
