package dev.example.proxy.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяет валидацию HTTP-полей и отсутствие общих изменяемых данных в копиях и снимках. */
class HeadersTest {
    @Test
    void preservesSeparateValuesAndUsesCaseInsensitiveNames() {
        var headers = new Headers().add("Set-Cookie", "one=1").add("set-cookie", "two=2");
        assertEquals(List.of("one=1", "two=2"), headers.get("SET-COOKIE"));
        assertEquals("one=1", headers.first("Set-Cookie"));
        assertEquals(1, headers.asMap().size());
        headers.set("Set-Cookie", "three=3");
        assertEquals(List.of("three=3"), headers.get("set-cookie"));
        headers.remove("SET-COOKIE");
        assertNull(headers.first("set-cookie"));
    }

    @Test
    void rejectsHeaderInjectionAndInvalidNames() {
        var headers = new Headers();
        for (String badValue : List.of("value\r\nX-Forged: true", "\n", "\0", "\u007f")) {
            assertThrows(IllegalArgumentException.class, () -> headers.set("x-safe", badValue));
        }
        for (String badName : List.of("", "foo bar", "foo:", "foo\r\n")) {
            assertThrows(IllegalArgumentException.class, () -> headers.set(badName, "safe"));
        }
        assertDoesNotThrow(() -> headers.set("x-safe", "tab\tvalue"));
    }

    @Test
    void incomingMetadataIsDeeplyImmutable() {
        var values = new ArrayList<>(List.of("original"));
        var source = new LinkedHashMap<String, List<String>>();
        source.put("X-Test", values);
        var incoming = new IncomingRequest("GET", "/", null, source, "127.0.0.1");
        values.add("changed");
        source.put("x-another", List.of("changed"));
        assertEquals(Map.of("x-test", List.of("original")), incoming.headers());
        assertThrows(UnsupportedOperationException.class, () -> incoming.headers().put("x", List.of("x")));
        assertThrows(UnsupportedOperationException.class, () -> incoming.headers().get("x-test").add("x"));
    }

    @Test
    void mutableCopiesDoNotShareRepeatedValueLists() {
        var source = new Headers().add("Set-Cookie", "a=1");
        Headers copy = source.copy();
        source.add("Set-Cookie", "b=2");
        copy.add("Set-Cookie", "c=3");
        assertEquals(List.of("a=1", "b=2"), source.get("Set-Cookie"));
        assertEquals(List.of("a=1", "c=3"), copy.get("Set-Cookie"));
    }

    @Test
    void directIncomingSnapshotMergesCaseVariantsAndStillValidatesEveryValue() {
        var first = new ArrayList<>(List.of("one"));
        var second = new ArrayList<>(List.of("two"));
        var source = new LinkedHashMap<String, List<String>>();
        source.put("X-Test", first);
        source.put("x-test", second);
        var incoming = new IncomingRequest("GET", "/", null, source, "127.0.0.1");
        first.clear();
        second.add("late");
        assertEquals(List.of("one", "two"), incoming.headers().get("x-test"));
        assertThrows(UnsupportedOperationException.class, () -> incoming.headers().get("x-test").set(0, "forged"));
        source.put("x-bad", List.of("ok", "injected\r\nX-Extra: true"));
        assertThrows(IllegalArgumentException.class,
                () -> new IncomingRequest("GET", "/", null, source, "127.0.0.1"));
    }

    @Test
    void scalarIterationPreservesSeparateValuesWithoutExposingMutableLists() {
        var headers = new Headers().add("Set-Cookie", "a=1").add("Set-Cookie", "b=2")
                .set("X-Test", "value");
        var emitted = new ArrayList<Map.Entry<String, String>>();
        headers.forEachValue((name, value) -> emitted.add(Map.entry(name, value)));
        headers.add("Set-Cookie", "late=3").remove("X-Test");
        assertEquals(List.of(Map.entry("set-cookie", "a=1"), Map.entry("set-cookie", "b=2"),
                Map.entry("x-test", "value")), emitted);
    }
}
