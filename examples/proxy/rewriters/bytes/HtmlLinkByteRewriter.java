package com.otr.portal.proxy.rewriters.bytes;

import com.otr.portal.hosts.Backend;
import com.otr.portal.proxy.rewriters.bytes.ByteTextSyntax.ByteStops;
import io.netty.buffer.ByteBuf;
import org.apache.commons.lang3.StringUtils;

/**
 * Байтовая реализация правил {@link com.otr.portal.proxy.rewriters.HtmlContentRewriter}.
 * <p>
 * Проходы и их порядок совпадают с regex-версией:
 * <ol>
 *     <li>{@code href=["|'](/.+?)["|']} → {@code href="<frontend без / в конце>$1"};</li>
 *     <li>поиск {@code <base\s+href=.+>} в результате первого прохода;</li>
 *     <li>без {@code <base>}: {@code href=["|']((\.\./|\./).+?)["|']} → {@code href="<frontend/>$1"};</li>
 *     <li>{@code <form(.+?)action="([^"]+?)"(.*?)>} (DOTALL) → вставка адреса фронтенда в значение action.</li>
 * </ol>
 */
public final class HtmlLinkByteRewriter implements ByteContentRewriter {
    private static final String HTML_SUBTYPE = "html";
    private static final byte[] HREF = ascii("href=");
    private static final byte[] BASE_TAG = ascii("<base");
    private static final byte[] FORM_TAG = ascii("<form");
    private static final byte[] FORM_ACTION = ascii("action=\"");
    private static final byte[] PARENT_DIRECTORY = ascii("../");
    private static final byte[] CURRENT_DIRECTORY = ascii("./");
    private static final byte[] QUOTE = ascii("\"");
    private static final ByteStops QUOTES = new ByteStops('"', '|', '\'');
    private static final ByteStops TAG_END = new ByteStops('>');

    @Override
    public ByteBuf rewrite(ByteRewriteContext context) {
        if (!HTML_SUBTYPE.equals(context.getContentType().getSubtype())) {
            return context.getSource();
        }
        Backend backend = context.getBackend();
        ByteTextSyntax syntax = context.getSyntax();
        // Вставки кодируются один раз на тело: адрес фронтенда портала вместо открывающей кавычки
        // (href="/a" → href="<фронтенд>/a") и перед значением action формы.
        String fullFrontendUrl = backend.fullFrontendUrl("");
        byte[] absoluteOpening = syntax.encode("\"" + StringUtils.removeEnd(fullFrontendUrl, "/"));
        byte[] relativeOpening = syntax.encode("\"" + backend.fullFrontendUrl("/"));
        byte[] formPrefix = syntax.encode(fullFrontendUrl);

        // Каждый проход работает с результатом предыдущего, как цепочка replaceAll в regex-версии.
        ByteBuf source = context.getSource();
        ByteBuf current = source;
        try {
            current = advance(source, current, rewriteHrefs(context.withSource(current), HrefKind.ABSOLUTE, absoluteOpening));
            // Если в странице есть <base>, браузер разрешает относительные ссылки относительно него — их не трогаем.
            if (!containsBaseTag(current, syntax)) {
                current = advance(source, current, rewriteHrefs(context.withSource(current), HrefKind.RELATIVE, relativeOpening));
            }
            return advance(source, current, rewriteFormActions(context.withSource(current), formPrefix));
        } catch (RuntimeException e) {
            if (current != source) {
                current.release();
            }
            throw e;
        }
    }

    /**
     * Переход к результату очередного прохода: промежуточный буфер больше не нужен и освобождается,
     * исходный остаётся за вызывающим (см. контракт {@link ByteContentRewriter}).
     */
    private static ByteBuf advance(ByteBuf source, ByteBuf current, ByteBuf next) {
        if (next != current && current != source) {
            current.release();
        }
        return next;
    }

    /**
     * Один проход по {@code href=}: у каждого совпадения открывающая кавычка заменяется на кавычку с адресом
     * фронтенда, а закрывающая нормализуется в {@code "} — как в замене {@code href="...$1"} regex-версии.
     * Сама ссылка не копируется: меняются только байты кавычек.
     */
    private static ByteBuf rewriteHrefs(ByteRewriteContext context, HrefKind kind, byte[] opening) {
        ByteBuf buf = context.getSource();
        ByteTextSyntax syntax = context.getSyntax();
        int end = buf.writerIndex();
        ByteSplice splice = new ByteSplice(buf, context.getAllocator());
        try {
            int href = ByteSearch.indexOf(buf, buf.readerIndex(), end, HREF);
            while (href >= 0) {
                int closing = findHrefClosingQuote(buf, href, end, kind, syntax);
                if (closing < 0) {
                    href = ByteSearch.indexOf(buf, href + 1, end, HREF);
                    continue;
                }
                int openingQuote = href + HREF.length;
                // Замена, которая ничего не меняет, не добавляется — чтобы не дробить результат лишними компонентами.
                if (!isPlainQuote(buf, openingQuote) || opening.length != QUOTE.length) {
                    splice.replace(openingQuote, openingQuote + 1, opening);
                }
                if (!isPlainQuote(buf, closing)) {
                    splice.replace(closing, closing + 1, QUOTE);
                }
                // Поиск продолжается после совпадения: regex тоже не ищет пересекающиеся совпадения.
                href = ByteSearch.indexOf(buf, closing + 1, end, HREF);
            }
            return splice.finish();
        } catch (RuntimeException e) {
            splice.discard();
            throw e;
        }
    }

    /**
     * Сопоставление {@code ["|'](prefix.+?)["|']} сразу после {@code href=}.
     *
     * @return индекс закрывающей кавычки или -1
     */
    private static int findHrefClosingQuote(ByteBuf buf, int href, int end, HrefKind kind, ByteTextSyntax syntax) {
        int openingQuote = href + HREF.length;
        if (openingQuote >= end || !QUOTES.contains(buf.getByte(openingQuote))) {
            return -1;
        }
        int prefixLength = kind.matchPrefix(buf, openingQuote + 1);
        if (prefixLength < 0) {
            return -1;
        }
        // .+? требует хотя бы один символ, не являющийся концом строки, и ищет ближайшую кавычку в той же строке.
        int firstChar = openingQuote + 1 + prefixLength;
        if (firstChar >= end || syntax.isLineTerminator(buf, firstChar, end)) {
            return -1;
        }
        return syntax.findOnLine(buf, firstChar + 1, end, QUOTES);
    }

    private static boolean containsBaseTag(ByteBuf buf, ByteTextSyntax syntax) {
        int end = buf.writerIndex();
        int base = ByteSearch.indexOf(buf, buf.readerIndex(), end, BASE_TAG);
        while (base >= 0) {
            if (isBaseTagMatch(buf, base, end, syntax)) {
                return true;
            }
            base = ByteSearch.indexOf(buf, base + 1, end, BASE_TAG);
        }
        return false;
    }

    /**
     * Сопоставление {@code <base\s+href=.+>} с позиции {@code base}.
     */
    private static boolean isBaseTagMatch(ByteBuf buf, int base, int end, ByteTextSyntax syntax) {
        int afterTag = base + BASE_TAG.length;
        int attribute = afterTag;
        while (attribute < end && isRegexWhitespace(buf.getByte(attribute))) {
            attribute++;
        }
        if (attribute == afterTag || !ByteSearch.startsWith(buf, attribute, HREF)) {
            return false;
        }
        int firstChar = attribute + HREF.length;
        return firstChar < end
                && !syntax.isLineTerminator(buf, firstChar, end)
                && syntax.findOnLine(buf, firstChar + 1, end, TAG_END) >= 0;
    }

    /**
     * Первый {@code action="} с непустым значением, стоящий не раньше чем через символ после {@code <form},
     * получает префикс; совпадение заканчивается первым {@code >} после закрывающей кавычки.
     * Если для очередного {@code <form} совпадения нет, его нет и для всех следующих.
     */
    private static ByteBuf rewriteFormActions(ByteRewriteContext context, byte[] formPrefix) {
        ByteBuf buf = context.getSource();
        int end = buf.writerIndex();
        ByteSplice splice = new ByteSplice(buf, context.getAllocator());
        try {
            int form = ByteSearch.indexOf(buf, buf.readerIndex(), end, FORM_TAG);
            while (form >= 0) {
                int value = findNonEmptyActionValue(buf, form + FORM_TAG.length + 1, end);
                if (value < 0) {
                    break;
                }
                // Режим DOTALL: кавычка и конец тега ищутся без ограничения строкой.
                int closing = buf.indexOf(value + 1, end, (byte) '"');
                int tagEnd = closing < 0 ? -1 : buf.indexOf(closing + 1, end, (byte) '>');
                if (tagEnd < 0) {
                    break;
                }
                splice.insert(value, formPrefix);
                form = ByteSearch.indexOf(buf, tagEnd + 1, end, FORM_TAG);
            }
            return splice.finish();
        } catch (RuntimeException e) {
            splice.discard();
            throw e;
        }
    }

    private static int findNonEmptyActionValue(ByteBuf buf, int from, int end) {
        int action = ByteSearch.indexOf(buf, from, end, FORM_ACTION);
        while (action >= 0) {
            int value = action + FORM_ACTION.length;
            if (value < end && buf.getByte(value) != '"') {
                return value;
            }
            action = ByteSearch.indexOf(buf, action + 1, end, FORM_ACTION);
        }
        return -1;
    }

    private static boolean isPlainQuote(ByteBuf buf, int index) {
        return buf.getByte(index) == '"';
    }

    private static boolean isRegexWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\n' || value == 0x0B || value == '\f' || value == '\r';
    }

    private static byte[] ascii(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private enum HrefKind {
        ABSOLUTE {
            @Override
            int matchPrefix(ByteBuf buf, int index) {
                return index < buf.writerIndex() && buf.getByte(index) == '/' ? 1 : -1;
            }
        },
        RELATIVE {
            @Override
            int matchPrefix(ByteBuf buf, int index) {
                if (ByteSearch.startsWith(buf, index, PARENT_DIRECTORY)) {
                    return PARENT_DIRECTORY.length;
                }
                return ByteSearch.startsWith(buf, index, CURRENT_DIRECTORY) ? CURRENT_DIRECTORY.length : -1;
            }
        };

        /**
         * @return длина обязательного начала значения ссылки или -1
         */
        abstract int matchPrefix(ByteBuf buf, int index);
    }
}
