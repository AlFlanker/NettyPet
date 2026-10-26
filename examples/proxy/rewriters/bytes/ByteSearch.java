package com.otr.portal.proxy.rewriters.bytes;

import io.netty.buffer.ByteBuf;
import lombok.experimental.UtilityClass;

/**
 * Поиск байтовых последовательностей в {@link ByteBuf} без копирования.
 */
@UtilityClass
class ByteSearch {

    /**
     * @return индекс начала {@code needle} в {@code [from, to)} или -1
     */
    static int indexOf(ByteBuf buf, int from, int to, byte[] needle) {
        int lastStart = to - needle.length;
        int index = from;
        while (index <= lastStart) {
            int found = buf.indexOf(index, lastStart + 1, needle[0]);
            if (found < 0) {
                return -1;
            }
            if (startsWith(buf, found, needle)) {
                return found;
            }
            index = found + 1;
        }
        return -1;
    }

    static boolean startsWith(ByteBuf buf, int at, byte[] prefix) {
        if (at < 0 || at + prefix.length > buf.writerIndex()) {
            return false;
        }
        for (int i = 1; i < prefix.length; i++) {
            if (buf.getByte(at + i) != prefix[i]) {
                return false;
            }
        }
        return prefix.length == 0 || buf.getByte(at) == prefix[0];
    }
}
