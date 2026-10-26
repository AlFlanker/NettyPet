package dev.example.jettyclient.proxy.policies;

import dev.example.jettyclient.proxy.ProxyRequestMetadata;
import dev.example.jettyclient.proxy.ProxyResponseBody;
import dev.example.jettyclient.proxy.ProxyResponseBodyPolicy;
import dev.example.jettyclient.proxy.ProxyResponseMetadata;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class LinkRewritePolicyTest {
    private static final URI BACKEND = URI.create("http://backend.example/internal");
    private static final URI FRONTEND = URI.create("https://portal.example/apps/service");
    private static final long LIMIT = 16 * 1024 * 1024;
    private static final ProxyRequestMetadata REQUEST = new ProxyRequestMetadata("GET", BACKEND, Map.of());

    @ParameterizedTest
    @ValueSource(strings = {
            "<a href=\"/main.css\">main</a>",
            "<a href='../js/main.js'>js</a><a href=|./other|>other</a>",
            "<a href='//cdn.example/x'>cdn</a><a href=\"https://outside.example/a\">outside</a>",
            "<base href='/assets/'><a href='./other'>relative</a>",
            "<base\nhref='/assets/'><a href='../other'>relative</a>",
            "<base href=\n'/assets/'><a href='./other'>relative</a>",
            "<base href=x\n<base href='/later'><a href='./other'>later base</a>",
            "<BASE href='/assets/'><A HREF='/UPPER'><a href='./other'>case-sensitive</a>",
            "<a href='/a\nb'><a href='./a\rb'><a href='/a\u2028b'><a href='/a\u0085b'>line ends</a>",
            "<p>Привет 🌍</p><a href='/стиль.css'><a href='./脚本.js'>unicode</a>",
            "<form class=\"test\" action=\"/submit\"><input></form>",
            "<form\nclass=\"test\"\naction=\"relative\"\nmethod=\"POST\">test</form>",
            "<form action=\"\"></form><form action=\"https://outside.example/action\">legacy prefix</form>",
            "<a href=\"/\"><a href='../'>empty tails follow the legacy regex</a>",
            "const url='http://backend.example/internal/api';<a href='/main'>main</a>"
    })
    void matchesTheLegacyRegexRulesAcrossOneByteSegments(String source) throws Exception {
        LinkRewritePolicy policy = policy(LIMIT, LIMIT);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        ProxyResponseBody rewritten = rewrite(policy, body(bytes, 1), "text/html; charset=UTF-8");
        assertEquals(reference(source, true), new String(bytes(rewritten), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/css", "text/javascript", "application/javascript", "application/x-javascript"})
    void cssAndJavascriptReplaceAbsoluteBackendUrlsWithoutTreatingTheirTextAsHtml(String mime) throws Exception {
        String source = "href='/unchanged' url(http://backend.example/internal/a.css) \"http://backend.example/internal/b.js\"";
        ProxyResponseBody rewritten = rewrite(policy(LIMIT, LIMIT), body(source.getBytes(StandardCharsets.UTF_8), 7), mime);
        assertEquals(reference(source, false), new String(bytes(rewritten), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ISO-8859-1", "windows-1251", "US-ASCII"})
    void asciiCompatibleSingleByteCharsetsKeepByteModeAndBorrowUnchangedRanges(String name) throws Exception {
        Charset charset = Charset.forName(name);
        String source = "before <a href='/main'>body</a> http://backend.example/internal/api";
        byte[] inputBytes = source.getBytes(charset);
        ProxyResponseBody input = body(inputBytes, 5);
        ProxyResponseBody output = rewrite(policy(LIMIT, LIMIT), input, "text/html; charset=\"" + name + "\"");
        assertEquals(reference(source, true), new String(bytes(output), charset));
        inputBytes[0] = 'B';
        assertEquals('B', output.buffers().getFirst().get(0), "Unchanged ranges must share the input memory");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-16", "UTF-16BE", "UTF-16LE"})
    void nonAsciiCompatibleCharsetsUseTheBoundedFallbackAndMatchLegacyTextRules(String name) throws Exception {
        Charset charset = Charset.forName(name);
        String source = "Привет <a href='/main'>🌍</a><form action=\"/post\">body</form> http://backend.example/internal/api";
        ProxyResponseBody output = rewrite(policy(LIMIT, LIMIT), body(source.getBytes(charset), 3),
                "text/html; charset=" + name);
        assertEquals(reference(source, true), new String(bytes(output), charset));
    }

    @Test
    void unknownCharsetMatchesTheOldUtf8DefaultAndQuotedOtherParametersDoNotChangeCharset() throws Exception {
        String source = "Привет <a href='/main'>link</a>";
        for (String type : List.of("text/html; charset=not-a-real-charset", "text/html; note=\"; charset=UTF-16\"")) {
            ProxyResponseBody output = rewrite(policy(LIMIT, LIMIT), body(source.getBytes(StandardCharsets.UTF_8), 2), type);
            assertEquals(reference(source, true), new String(bytes(output), StandardCharsets.UTF_8));
        }
    }

    @Test
    void preservesAnUnchangedEightMebibyteDirectBodyWithoutWholeBodyCopies() throws Exception {
        List<ByteBuffer> direct = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            ByteBuffer segment = ByteBuffer.allocateDirect(65_536);
            while (segment.hasRemaining()) segment.put((byte)'x');
            segment.flip();
            direct.add(segment);
        }
        ProxyResponseBody input = ProxyResponseBody.of(direct);
        ProxyResponseBody output = rewrite(policy(LIMIT, LIMIT), input, "text/html; charset=UTF-8");
        assertSame(input, output, "No edit must return the original body container");
        assertEquals(8 * 1024 * 1024, output.length());
        assertTrue(output.buffers().stream().allMatch(ByteBuffer::isDirect));
        assertTrue(direct.stream().allMatch(buffer -> buffer.position() == 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-8", "UTF-16BE"})
    void malformedRepeatedBaseAndFormPrefixesDoNotRetryTheWholeRemainingDocument(String charsetName) {
        Charset charset = Charset.forName(charsetName);
        String source = "<base href=x <form ".repeat(60_000);
        byte[] bytes = source.getBytes(charset);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            ProxyResponseBody result = rewrite(policy(LIMIT, LIMIT), body(bytes, 65_536),
                    "text/html; charset=" + charsetName);
            assertArrayEquals(bytes, bytes(result));
        });
    }

    @Test
    void binaryAndUnselectedMimeTypesDoNotEnterAWholeBodyRewrite() {
        LinkRewritePolicy policy = policy(LIMIT, LIMIT);
        for (String type : List.of("application/octet-stream", "image/png", "text/plain", "application/json"))
            assertNull(policy.select(REQUEST, response(type)));
        assertNull(policy.select(REQUEST, new ProxyResponseMetadata(200, Map.of())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip, identity;q=0", "gzip, *;q=0"})
    void anIdentityExcludingClientKeepsTheOriginRepresentation(String preferences) {
        ProxyRequestMetadata request = new ProxyRequestMetadata("GET", BACKEND,
                Map.of("Accept-Encoding", List.of(preferences)));
        assertNull(policy(LIMIT, LIMIT).select(request, response("text/html")));
    }

    @Test
    void identityPreferencesAreCombinedAcrossHeaderFieldsWithExplicitIdentityTakingPrecedence() {
        LinkRewritePolicy policy = policy(LIMIT, LIMIT);
        ProxyRequestMetadata denied = new ProxyRequestMetadata("GET", BACKEND,
                Map.of("Accept-Encoding", List.of("gzip", "identity;q=0")));
        assertNull(policy.select(denied, response("text/html")));
        ProxyRequestMetadata permitted = new ProxyRequestMetadata("GET", BACKEND,
                Map.of("Accept-Encoding", List.of("*;q=0", "identity;q=0.5")));
        assertNotNull(policy.select(permitted, response("text/html")));
    }

    @Test
    void inputAndExpandingOutputAreRejectedByTheApplicationBudget() {
        LinkRewritePolicy inputLimited = policy(3, LIMIT);
        assertThrows(RewriteLimitException.class, () -> rewrite(inputLimited, body(new byte[4], 2), "text/html"));
        LinkRewritePolicy outputLimited = policy(LIMIT, 20);
        assertThrows(RewriteLimitException.class,
                () -> rewrite(outputLimited, body("<a href='/x'>".getBytes(StandardCharsets.UTF_8), 4), "text/html"));
    }

    @Test
    void shrinkingReplacementCanFitAnOutputBudgetSmallerThanTheInput() throws Exception {
        var settings = new LinkRewriteSettings(URI.create("http://a-very-long-backend.example/internal"),
                URI.create("https://p.example/x"), LIMIT, 64);
        String source = settings.backendBase() + "/one " + settings.backendBase() + "/two";
        ProxyResponseBody output = rewrite(new LinkRewritePolicy(settings), body(source.getBytes(StandardCharsets.UTF_8), 5), "text/css");
        assertEquals("https://p.example/x/one https://p.example/x/two", new String(bytes(output), StandardCharsets.UTF_8));
    }

    @Test
    void excessiveEditComponentsAreRejectedBeforeCreatingAnUnboundedOutputList() {
        byte[] input = "<a href='/x'>".repeat(20_000).getBytes(StandardCharsets.UTF_8);
        assertThrows(RewriteLimitException.class,
                () -> rewrite(policy(LIMIT, LIMIT), body(input, 65_536), "text/html"));
    }

    @Test
    void genericCharsetFallbackAlsoHonorsTheEncodedOutputBudget() {
        byte[] input = "<a href='/x'>".getBytes(StandardCharsets.UTF_16BE);
        assertThrows(RewriteLimitException.class,
                () -> rewrite(policy(LIMIT, 45), body(input, 3), "text/html; charset=UTF-16BE"));
    }

    @Test
    void settingsValidateBaseUrlsAndCanonicalizeTrailingSlashes() {
        assertEquals(BACKEND, new LinkRewriteSettings(URI.create(BACKEND + "/"), FRONTEND, 1, 1).backendBase());
        assertThrows(IllegalArgumentException.class, () -> new LinkRewriteSettings(URI.create("file:///x"), FRONTEND, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LinkRewriteSettings(URI.create("http://user@backend/x"), FRONTEND, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LinkRewriteSettings(URI.create("http://backend/x?q=1"), FRONTEND, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LinkRewriteSettings(BACKEND, FRONTEND, 0, 1));
    }

    @Test
    void onlyTheJoinSlashIsRemovedWhileAnIntentionalDuplicateBaseSlashIsPreserved() throws Exception {
        LinkRewriteSettings settings = new LinkRewriteSettings(URI.create("http://backend.example/internal//"),
                URI.create("https://portal.example/apps/service//"), LIMIT, LIMIT);
        assertEquals("/internal/", settings.backendBase().getRawPath());
        assertEquals("/apps/service/", settings.frontendBase().getRawPath());
        String source = "http://backend.example/internal//asset.css http://backend.example/internal/other.css";
        ProxyResponseBody result = rewrite(new LinkRewritePolicy(settings), body(source.getBytes(StandardCharsets.UTF_8), 7), "text/css");
        assertEquals("https://portal.example/apps/service//asset.css http://backend.example/internal/other.css",
                new String(bytes(result), StandardCharsets.UTF_8));
    }

    private static LinkRewritePolicy policy(long input, long output) {
        return new LinkRewritePolicy(new LinkRewriteSettings(BACKEND, FRONTEND, input, output));
    }
    private static ProxyResponseMetadata response(String type) {
        return new ProxyResponseMetadata(200, Map.of("Content-Type", List.of(type)));
    }
    private static ProxyResponseBody rewrite(LinkRewritePolicy policy, ProxyResponseBody body, String type) throws Exception {
        ProxyResponseMetadata response = response(type);
        ProxyResponseBodyPolicy.Selection selection = policy.select(REQUEST, response);
        assertNotNull(selection);
        return selection.rewriter().rewrite(response, body);
    }
    private static ProxyResponseBody body(byte[] bytes, int chunk) {
        List<ByteBuffer> buffers = new ArrayList<>();
        for (int offset = 0; offset < bytes.length; offset += chunk)
            buffers.add(ByteBuffer.wrap(bytes, offset, Math.min(chunk, bytes.length - offset)).slice());
        return ProxyResponseBody.of(buffers);
    }
    private static byte[] bytes(ProxyResponseBody body) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (ByteBuffer buffer : body.buffers()) {
            byte[] part = new byte[buffer.remaining()];
            buffer.get(part);
            output.writeBytes(part);
        }
        return output.toByteArray();
    }
    private static String reference(String source, boolean html) {
        String frontend = FRONTEND.toASCIIString();
        String rewritten = source.replace(BACKEND + "/", frontend + "/");
        if (!html) return rewritten;
        rewritten = Pattern.compile("href=[\"|'](/.+?)[\"|']").matcher(rewritten)
                .replaceAll("href=\"" + Matcher.quoteReplacement(frontend) + "$1\"");
        if (!Pattern.compile("<base\\s+href=.+>.*").matcher(rewritten).find())
            rewritten = Pattern.compile("href=[\"|']((\\.\\./|\\./).+?)[\"|']").matcher(rewritten)
                    .replaceAll("href=\"" + Matcher.quoteReplacement(frontend + "/") + "$1\"");
        return Pattern.compile("<form(.+?)action=\"([^\"]+?)\"(.*?)>", Pattern.DOTALL).matcher(rewritten)
                .replaceAll("<form$1action=\"" + Matcher.quoteReplacement(frontend) + "$2\"$3>");
    }
}
