package com.otr.portal.proxy.rewriters.bytes;

import io.netty.buffer.ByteBuf;
import lombok.experimental.UtilityClass;

/**
 * Байтовый аналог {@link org.apache.commons.lang3.StringUtils#replace(String, String, String)}.
 */
@UtilityClass
public class LiteralByteRewriter {

    public static ByteBuf replaceLiteral(ByteRewriteContext context, String search, String replacement) {
        ByteBuf source = context.getSource();
        if (search.isEmpty()) {
            return source;
        }
        ByteTextSyntax syntax = context.getSyntax();
        // Строка, непредставимая в кодировке тела, в нём встретиться не может.
        byte[] needle = syntax.encodeNeedle(search);
        if (needle == null) {
            return source;
        }
        int end = source.writerIndex();
        int found = ByteSearch.indexOf(source, source.readerIndex(), end, needle);
        if (found < 0) {
            return source;
        }
        byte[] insertion = syntax.encode(replacement);
        ByteSplice splice = new ByteSplice(source, context.getAllocator());
        try {
            while (found >= 0) {
                splice.replace(found, found + needle.length, insertion);
                found = ByteSearch.indexOf(source, found + needle.length, end, needle);
            }
            return splice.finish();
        } catch (RuntimeException e) {
            splice.discard();
            throw e;
        }
    }
}
