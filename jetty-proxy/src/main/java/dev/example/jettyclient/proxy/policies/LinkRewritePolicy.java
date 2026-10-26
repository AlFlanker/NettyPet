package dev.example.jettyclient.proxy.policies;

import dev.example.jettyclient.proxy.ProxyRequestMetadata;
import dev.example.jettyclient.proxy.ProxyResponseBody;
import dev.example.jettyclient.proxy.ProxyResponseBodyPolicy;
import dev.example.jettyclient.proxy.ProxyResponseMetadata;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.http.HttpField;

/**
 * Portable counterparts of the example FullLinkContentRewriter and HtmlContentRewriter.
 * These deliberately preserve the old case-sensitive href/base/form rules, including
 * prefixing every non-empty double-quoted form action. This is not an HTML parser.
 * UTF-8 and ASCII-compatible single-byte charsets borrow unchanged input segments.
 * Other supported charsets (for example UTF-16) use an explicitly bounded String
 * fallback on the engine's rewrite worker; they create complete heap text copies.
 */
public final class LinkRewritePolicy implements ProxyResponseBodyPolicy {
    private static final SegmentedText.Needle HREF = needle("href=");
    private static final SegmentedText.Needle BASE = needle("<base");
    private static final SegmentedText.Needle FORM = needle("<form");
    private static final SegmentedText.Needle ACTION = needle("action=\"");
    private static final byte[] PARENT = ascii("../");
    private static final byte[] CURRENT = ascii("./");
    private static final byte[] QUOTE = ascii("\"");
    private static final Map<Charset, Syntax> SYNTAXES = new ConcurrentHashMap<>();
    private final LinkRewriteSettings settings;
    private final String backendSlash;
    private final String frontend;
    private final String frontendSlash;
    private final boolean legacySelection;
    private final Map<Charset, Encoded> encoded = new ConcurrentHashMap<>();

    public LinkRewritePolicy(LinkRewriteSettings settings) {
        this(settings, false);
    }

    /**
     * Opt-in selector matching the portal example: CSS/WCS target paths (matrix
     * suffix removed, case-insensitive) or a CSS MIME subtype are text assets even
     * when Content-Type is missing/wrong. Otherwise only text/html is selected,
     * with the example's case-sensitive image/font path exclusions. Response
     * metadata is never replaced: its actual charset and subtype still determine
     * decoding and whether the HTML-specific passes run.
     */
    public static LinkRewritePolicy legacyCompatible(LinkRewriteSettings settings) {
        return new LinkRewritePolicy(settings, true);
    }

    private LinkRewritePolicy(LinkRewriteSettings settings, boolean legacySelection) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.legacySelection = legacySelection;
        backendSlash = settings.backendBase().toASCIIString() + "/";
        frontend = settings.frontendBase().toASCIIString();
        frontendSlash = frontend + "/";
    }

    @Override public Selection select(ProxyRequestMetadata request, ProxyResponseMetadata response) {
        // Without an application-provided outbound coding strategy, never force
        // an identity representation on a client which explicitly excludes it.
        if (!ContentCodingPreferences.identityAllowed(request.headerValues("Accept-Encoding"))) return null;
        List<String> contentTypes = response.headerValues("Content-Type");
        Map<String, String> parameters = new LinkedHashMap<>();
        String mime = Objects.toString(contentTypes.isEmpty() ? null
                        : HttpField.getValueParameters(contentTypes.getFirst(), parameters), "")
                .trim().toLowerCase(Locale.ROOT);
        int slash = mime.indexOf('/');
        String subtype = slash < 0 ? "" : mime.substring(slash + 1);
        if (legacySelection ? !legacyTextAsset(request, mime, subtype)
                : !List.of("text/html", "text/css", "text/javascript", "application/javascript", "application/x-javascript")
                        .contains(mime)) return null;
        Charset charset = StandardCharsets.UTF_8;
        for (var parameter : parameters.entrySet()) {
            if (parameter.getKey().equalsIgnoreCase("charset")) {
                try { charset = Charset.forName(parameter.getValue()); }
                catch (IllegalArgumentException unsupported) { charset = StandardCharsets.UTF_8; }
            }
        }
        Encoded literals = encoded.computeIfAbsent(charset, this::encode);
        boolean html = legacySelection ? subtype.equals("html") : mime.equals("text/html");
        return new Selection(settings.maxInputBytes(), settings.maxOutputBytes(), (metadata, input) -> {
            if (input.length() > settings.maxInputBytes()) throw new RewriteLimitException("Rewrite input limit exceeded");
            if (!literals.syntax.byteMode) return rewriteFallback(input, literals.syntax.charset, html);
            ProxyResponseBody result = replaceAbsolute(input, literals);
            if (html) {
                result = rewriteHrefs(result, literals, false);
                if (!containsBase(new SegmentedText(result), literals.syntax)) result = rewriteHrefs(result, literals, true);
                result = rewriteForms(result, literals);
            }
            if (result.length() > settings.maxOutputBytes()) throw new RewriteLimitException("Rewrite output limit exceeded");
            return result;
        });
    }

    private static boolean legacyTextAsset(ProxyRequestMetadata request, String mime, String subtype) {
        String rawPath = Objects.toString(request.target().getRawPath(), "");
        // getRawPath already excludes the query and must not decode %2E/%3B.
        int matrix = rawPath.indexOf(';');
        String assetPath = (matrix < 0 ? rawPath : rawPath.substring(0, matrix)).toLowerCase(Locale.ROOT);
        if (assetPath.endsWith(".css") || assetPath.endsWith(".wcs") || subtype.equals("css")) return true;
        if (!mime.equals("text/html")) return false;
        // Deliberately retain the old case-sensitive contains(), not a new suffix heuristic.
        for (String binary : List.of(".png", ".jpg", ".woff", ".ttf", ".jpeg", ".gif"))
            if (rawPath.contains(binary)) return false;
        return true;
    }

    private Encoded encode(Charset charset) {
        Syntax syntax = SYNTAXES.computeIfAbsent(charset, Syntax::new);
        return new Encoded(syntax, new SegmentedText.Needle(backendSlash.getBytes(charset)),
                frontendSlash.getBytes(charset), ("\"" + frontend).getBytes(charset),
                ("\"" + frontendSlash).getBytes(charset), frontend.getBytes(charset));
    }

    private ProxyResponseBody replaceAbsolute(ProxyResponseBody input, Encoded literals) {
        if (Arrays.equals(literals.backend.bytes, literals.frontendSlash)) return input;
        SegmentedText source = new SegmentedText(input);
        BodySplice edits = new BodySplice(source, settings.maxOutputBytes());
        for (long found = source.find(literals.backend, 0); found >= 0;
                found = source.find(literals.backend, found + literals.backend.bytes.length))
            edits.replace(found, found + literals.backend.bytes.length, literals.frontendSlash);
        return edits.finish();
    }

    private ProxyResponseBody rewriteHrefs(ProxyResponseBody input, Encoded literals, boolean relative) {
        SegmentedText source = new SegmentedText(input);
        BodySplice edits = new BodySplice(source, settings.maxOutputBytes());
        byte[] opening = relative ? literals.relativeOpening : literals.absoluteOpening;
        long href = source.find(HREF, 0);
        while (href >= 0) {
            long quote = href + HREF.bytes.length;
            long closing = hrefEnd(source, quote, literals.syntax, relative);
            if (closing < 0) { href = source.find(HREF, href + 1); continue; }
            if (source.get(quote) != '"' || opening.length != 1) edits.replace(quote, quote + 1, opening);
            if (source.get(closing) != '"') edits.replace(closing, closing + 1, QUOTE);
            href = source.find(HREF, closing + 1);
        }
        return edits.finish();
    }

    private static long hrefEnd(SegmentedText source, long quote, Syntax syntax, boolean relative) {
        if (quote >= source.length || !isQuote(source.get(quote))) return -1;
        long value = quote + 1;
        int prefix;
        if (relative) prefix = source.startsWith(value, PARENT) ? PARENT.length
                : source.startsWith(value, CURRENT) ? CURRENT.length : -1;
        else prefix = value < source.length && source.get(value) == '/' ? 1 : -1;
        if (prefix < 0) return -1;
        long first = value + prefix;
        if (first >= source.length || syntax.lineEnd(source, first)) return -1;
        return findOnLine(source, first + 1, syntax, true);
    }

    private static boolean containsBase(SegmentedText source, Syntax syntax) {
        long base = source.find(BASE, 0);
        while (base >= 0) {
            long after = base + BASE.bytes.length;
            long attribute = after;
            while (attribute < source.length && whitespace(source.get(attribute))) attribute++;
            if (attribute == after || !source.startsWith(attribute, HREF.bytes)) {
                base = source.find(BASE, base + 1);
                continue;
            }
            long first = attribute + HREF.bytes.length;
            if (first >= source.length) return false;
            if (syntax.lineEnd(source, first)) { base = source.find(BASE, first + 1); continue; }
            long at = first + 1;
            while (at < source.length && source.get(at) != '>' && !syntax.lineEnd(source, at)) at++;
            if (at == source.length) return false;
            if (source.get(at) == '>') return true;
            // No later candidate on this same line can have a closing tag either.
            base = source.find(BASE, at + 1);
        }
        return false;
    }

    private static long findOnLine(SegmentedText source, long from, Syntax syntax, boolean quotes) {
        for (long at = from; at < source.length; at++) {
            byte value = source.get(at);
            if (quotes ? isQuote(value) : value == '>') return at;
            if (syntax.lineEnd(source, at)) return -1;
        }
        return -1;
    }

    private ProxyResponseBody rewriteForms(ProxyResponseBody input, Encoded literals) {
        SegmentedText source = new SegmentedText(input);
        BodySplice edits = new BodySplice(source, settings.maxOutputBytes());
        long form = source.find(FORM, 0);
        while (form >= 0) {
            long action = source.find(ACTION, form + FORM.bytes.length + 1);
            long value = -1;
            while (action >= 0) {
                long candidate = action + ACTION.bytes.length;
                if (candidate < source.length && source.get(candidate) != '"') { value = candidate; break; }
                action = source.find(ACTION, action + 1);
            }
            if (value < 0) break;
            long closing = source.findByte((byte)'"', value + 1);
            long end = closing < 0 ? -1 : source.findByte((byte)'>', closing + 1);
            if (end < 0) break;
            edits.replace(value, value, literals.formPrefix);
            form = source.find(FORM, end + 1);
        }
        return edits.finish();
    }

    private ProxyResponseBody rewriteFallback(ProxyResponseBody input, Charset charset, boolean html) throws Exception {
        if (input.length() > Integer.MAX_VALUE - 8) throw new RewriteLimitException("Charset fallback requires an int-sized body");
        byte[] bytes = new byte[(int)input.length()];
        int offset = 0;
        for (ByteBuffer buffer : input.buffers()) {
            int count = buffer.remaining();
            buffer.get(bytes, offset, count);
            offset += count;
        }
        String text = new String(bytes, charset);
        text = literalText(text);
        if (html) {
            text = hrefText(text, false);
            if (!containsBaseText(text)) text = hrefText(text, true);
            text = formText(text);
        }
        var encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer characters = CharBuffer.wrap(text);
        List<ByteBuffer> output = new ArrayList<>();
        long count = 0;
        boolean encodedAll = false;
        while (!encodedAll) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            var result = encoder.encode(characters, buffer, true);
            if (result.isError()) result.throwException();
            buffer.flip();
            count = addFallback(output, buffer, count);
            encodedAll = result.isUnderflow();
        }
        boolean flushed = false;
        while (!flushed) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            var result = encoder.flush(buffer);
            if (result.isError()) result.throwException();
            buffer.flip();
            count = addFallback(output, buffer, count);
            flushed = result.isUnderflow();
        }
        return ProxyResponseBody.of(output);
    }

    private long addFallback(List<ByteBuffer> output, ByteBuffer buffer, long count) {
        if (buffer.remaining() > settings.maxOutputBytes() - count)
            throw new RewriteLimitException("Charset fallback exceeds output byte limit");
        if (buffer.hasRemaining()) {
            if (output.size() == 65_536) throw new RewriteLimitException("Charset fallback exceeds component limit");
            output.add(buffer);
        }
        return count + buffer.remaining();
    }

    private String literalText(String source) {
        int found = source.indexOf(backendSlash);
        if (found < 0 || backendSlash.equals(frontendSlash)) return bounded(source);
        StringBuilder output = new StringBuilder(Math.min(source.length(), 8192));
        int copied = 0;
        while (found >= 0) {
            append(output, source, copied, found);
            append(output, frontendSlash, 0, frontendSlash.length());
            copied = found + backendSlash.length();
            found = source.indexOf(backendSlash, copied);
        }
        append(output, source, copied, source.length());
        return output.toString();
    }

    private String hrefText(String source, boolean relative) {
        TextSplice edits = new TextSplice(source);
        int href = source.indexOf("href=");
        while (href >= 0) {
            int quote = href + 5;
            int value = quote + 1;
            int prefix = -1;
            if (quote < source.length() && isQuote(source.charAt(quote))) {
                if (relative) prefix = source.startsWith("../", value) ? 3 : source.startsWith("./", value) ? 2 : -1;
                else prefix = value < source.length() && source.charAt(value) == '/' ? 1 : -1;
            }
            int first = value + prefix;
            if (prefix < 0 || first >= source.length() || lineEnd(source.charAt(first))) {
                href = source.indexOf("href=", href + 1);
                continue;
            }
            int closing = first + 1;
            while (closing < source.length() && !isQuote(source.charAt(closing)) && !lineEnd(source.charAt(closing))) closing++;
            if (closing == source.length() || !isQuote(source.charAt(closing))) {
                href = source.indexOf("href=", href + 1);
                continue;
            }
            edits.replace(quote, quote + 1, "\"" + (relative ? frontendSlash : frontend));
            if (source.charAt(closing) != '"') edits.replace(closing, closing + 1, "\"");
            href = source.indexOf("href=", closing + 1);
        }
        return edits.finish();
    }

    private static boolean containsBaseText(String source) {
        int base = source.indexOf("<base");
        while (base >= 0) {
            int after = base + 5;
            int attribute = after;
            while (attribute < source.length() && whitespace(source.charAt(attribute))) attribute++;
            if (attribute == after || !source.startsWith("href=", attribute)) {
                base = source.indexOf("<base", base + 1);
                continue;
            }
            int first = attribute + 5;
            if (first == source.length()) return false;
            if (lineEnd(source.charAt(first))) { base = source.indexOf("<base", first + 1); continue; }
            int at = first + 1;
            while (at < source.length() && source.charAt(at) != '>' && !lineEnd(source.charAt(at))) at++;
            if (at == source.length()) return false;
            if (source.charAt(at) == '>') return true;
            base = source.indexOf("<base", at + 1);
        }
        return false;
    }

    private String formText(String source) {
        TextSplice edits = new TextSplice(source);
        int form = source.indexOf("<form");
        while (form >= 0) {
            int action = source.indexOf("action=\"", form + 6);
            int value = -1;
            while (action >= 0) {
                int candidate = action + 8;
                if (candidate < source.length() && source.charAt(candidate) != '"') { value = candidate; break; }
                action = source.indexOf("action=\"", action + 1);
            }
            if (value < 0) break;
            int quote = source.indexOf('"', value + 1);
            int end = quote < 0 ? -1 : source.indexOf('>', quote + 1);
            if (end < 0) break;
            edits.replace(value, value, frontend);
            form = source.indexOf("<form", end + 1);
        }
        return edits.finish();
    }

    private final class TextSplice {
        private final String source;
        private StringBuilder output;
        private int copied;
        TextSplice(String source) { this.source = source; }
        void replace(int from, int to, String insertion) {
            if (output == null) output = new StringBuilder(Math.min(source.length(), 8192));
            append(output, source, copied, from);
            append(output, insertion, 0, insertion.length());
            copied = to;
        }
        String finish() {
            if (output == null) return bounded(source);
            append(output, source, copied, source.length());
            return output.toString();
        }
    }

    private String bounded(String text) {
        if (text.length() > settings.maxOutputBytes()) throw new RewriteLimitException("Charset fallback exceeds text budget");
        return text;
    }

    private void append(StringBuilder output, String source, int from, int to) {
        long size = (long)output.length() + to - from;
        if (size > settings.maxOutputBytes() || size > Integer.MAX_VALUE - 8)
            throw new RewriteLimitException("Charset fallback exceeds text budget");
        output.append(source, from, to);
    }

    private static boolean isQuote(byte value) { return value == '"' || value == '\'' || value == '|'; }
    private static boolean isQuote(char value) { return value == '"' || value == '\'' || value == '|'; }
    private static boolean whitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f' || value == 0x0B;
    }
    private static boolean whitespace(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f' || value == 0x0B;
    }
    private static boolean lineEnd(char value) {
        return value == '\n' || value == '\r' || value == '\u0085' || value == '\u2028' || value == '\u2029';
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static SegmentedText.Needle needle(String value) { return new SegmentedText.Needle(ascii(value)); }

    private record Encoded(Syntax syntax, SegmentedText.Needle backend, byte[] frontendSlash,
                           byte[] absoluteOpening, byte[] relativeOpening, byte[] formPrefix) { }

    private static final class Syntax {
        final Charset charset;
        final boolean utf8;
        final boolean byteMode;
        final boolean[] terminators = new boolean[256];
        Syntax(Charset charset) {
            this.charset = charset;
            utf8 = charset.equals(StandardCharsets.UTF_8);
            boolean compatible = utf8;
            if (!utf8 && charset.canEncode() && charset.newEncoder().maxBytesPerChar() == 1) {
                compatible = true;
                for (int value = 0; value < 256; value++) {
                    String decoded = new String(new byte[]{(byte)value}, charset);
                    if (decoded.length() != 1 || (value < 128 ? decoded.charAt(0) != value : decoded.charAt(0) < 128)) {
                        compatible = false;
                        break;
                    }
                    char character = decoded.charAt(0);
                    terminators[value] = character == '\n' || character == '\r' || character == '\u0085'
                            || character == '\u2028' || character == '\u2029';
                }
            }
            byteMode = compatible;
        }
        boolean lineEnd(SegmentedText source, long at) {
            byte value = source.get(at);
            if (!utf8) return terminators[value & 255];
            if (value == '\n' || value == '\r') return true;
            if (value == (byte)0xC2)
                return at + 1 < source.length && source.get(at + 1) == (byte)0x85;
            return value == (byte)0xE2 && at + 2 < source.length && source.get(at + 1) == (byte)0x80
                    && (source.get(at + 2) == (byte)0xA8 || source.get(at + 2) == (byte)0xA9);
        }
    }
}
