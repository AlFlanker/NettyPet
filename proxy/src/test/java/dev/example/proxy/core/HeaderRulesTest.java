package dev.example.proxy.core;

import dev.example.proxy.api.Headers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeaderRulesTest {
    @Test
    void removesEveryConnectionTokenAcrossMultipleHeaderLines() {
        var source = new Headers()
                .add("Connection", "keep-alive, X-Internal")
                .add("connection", "X-Another, Connection")
                .add("X-Internal", "one").add("x-another", "two")
                .add("Keep-Alive", "timeout=5")
                .add("Transfer-Encoding", "chunked")
                .add("Proxy-Connection", "keep-alive")
                .add("Host", "old.example")
                .add("Content-Length", "100")
                .add("Expect", "100-continue")
                .add("X-Application", "keep");
        Headers result = HeaderRules.request(source);
        assertEquals(java.util.Map.of("x-application", List.of("keep")), result.asMap());
        assertEquals(result.asMap(), HeaderRules.requestSnapshot(source));
        assertEquals("old.example", source.first("Host")); // copying does not mutate caller
    }

    @Test
    void responsePreservesContentLengthAndRepeatedCookies() {
        var source = new Headers().set("Content-Length", "123")
                .add("Set-Cookie", "a=1").add("Set-Cookie", "b=2")
                .set("Connection", "close");
        Headers result = HeaderRules.response(source);
        assertEquals("123", result.first("content-length"));
        assertEquals(List.of("a=1", "b=2"), result.get("set-cookie"));
        assertFalse(result.contains("connection"));
    }

    @Test
    void sanitizesFieldsIntroducedByProcessors() {
        var headers = new Headers().set("X-Secret", "private").set("Connection", "x-secret");
        assertSame(headers, HeaderRules.sanitize(headers));
        assertTrue(headers.asMap().isEmpty());
    }

    @Test
    void acceptsEmptyListElementsButRejectsInvalidConnectionTokens() {
        var valid = new Headers().set("Connection", ", , X-Hop,,\t,")
                .set("X-Hop", "remove").set("X-End-To-End", "keep");
        assertEquals(java.util.Map.of("x-end-to-end", List.of("keep")), HeaderRules.sanitize(valid).asMap());
        for (String token : List.of("x invalid", "\"quoted\"", "x:field", "x=y")) {
            var invalid = new Headers().set("Connection", token);
            assertThrows(IllegalArgumentException.class, () -> HeaderRules.sanitize(invalid));
            assertThrows(IllegalArgumentException.class, () -> HeaderRules.requestSnapshot(invalid));
        }
    }

    @Test
    void finalRequestSnapshotFiltersWithoutMutatingOrSharingTheProcessorPlan() {
        var source = new Headers().set("Connection", "X-Private, Connection")
                .set("X-Private", "secret").set("Host", "forged.example")
                .add("X-Repeated", "one").add("X-Repeated", "two");
        var snapshot = HeaderRules.requestSnapshot(source);
        assertEquals("secret", source.first("x-private"));
        assertEquals("forged.example", source.first("host"));
        source.add("X-Repeated", "late").set("X-New", "late");
        assertEquals(java.util.Map.of("x-repeated", List.of("one", "two")), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x-new", List.of("forged")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get("x-repeated").add("forged"));
    }
}
