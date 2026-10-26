package com.otr.portal.proxy.rewriters.bytes;

import io.netty.buffer.ByteBuf;
import io.netty.util.ByteProcessor;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Байтовое представление текста в кодировке, где ASCII-разметка HTML совпадает с ASCII-байтами:
 * UTF-8 и однобайтовые ASCII-совместимые кодировки (windows-1251, ISO-8859-*, KOI8-R...).
 * <p>
 * Воспроизводит понятие "конец строки" из {@link java.util.regex.Pattern} для {@code .}:
 * {@code \n}, {@code \r}, U+0085, U+2028, U+2029.
 * <p>
 * Побайтовый поиск ASCII-разметки корректен для таких кодировок: в UTF-8 байты многобайтовых
 * символов всегда ≥ 0x80 и не совпадают с ASCII, а в однобайтовых кодировках ASCII-байт - это сам символ.
 */
public final class ByteTextSyntax {
    public static final ByteTextSyntax UTF_8 = new ByteTextSyntax(StandardCharsets.UTF_8, utf8TerminatorLeads(), true);

    private static final int BYTE_VALUES = 256;
    private static final byte UTF8_NEXT_LINE_LEAD = (byte) 0xC2;
    private static final byte UTF8_NEXT_LINE_TAIL = (byte) 0x85;
    private static final byte UTF8_SEPARATOR_LEAD = (byte) 0xE2;
    private static final byte UTF8_SEPARATOR_MIDDLE = (byte) 0x80;
    private static final byte UTF8_LINE_SEPARATOR_TAIL = (byte) 0xA8;
    private static final byte UTF8_PARAGRAPH_SEPARATOR_TAIL = (byte) 0xA9;
    private static final Map<Charset, Optional<ByteTextSyntax>> SYNTAX_BY_CHARSET = new ConcurrentHashMap<>();

    @Getter
    private final Charset charset;
    private final boolean[] terminatorLeads;
    private final boolean utf8;

    private ByteTextSyntax(Charset charset, boolean[] terminatorLeads, boolean utf8) {
        this.charset = charset;
        this.terminatorLeads = terminatorLeads;
        this.utf8 = utf8;
    }

    /**
     * @return синтаксис для кодировки или {@code null}, если текст в ней нельзя разбирать по байтам
     */
    @Nullable
    public static ByteTextSyntax forCharset(Charset charset) {
        if (StandardCharsets.UTF_8.equals(charset)) {
            return UTF_8;
        }
        return SYNTAX_BY_CHARSET.computeIfAbsent(charset, ByteTextSyntax::createSingleByteSyntax)
                .orElse(null);
    }

    /**
     * Кодирует литерал для поиска. Если символы литерала в кодировке непредставимы,
     * литерал в тексте встретиться не может.
     */
    @Nullable
    byte[] encodeNeedle(String text) {
        CharsetEncoder encoder = charset.newEncoder();
        return encoder.canEncode(text)
                ? text.getBytes(charset)
                : null;
    }

    byte[] encode(String text) {
        return text.getBytes(charset);
    }

    /**
     * Позиция первого байта из {@code stops} в {@code [from, to)}, если до него нет конца строки.
     * Байты-стопы должны быть ASCII.
     *
     * @return индекс или -1
     */
    int findOnLine(ByteBuf buf, int from, int to, ByteStops stops) {
        // Быстрый проход останавливается на стопе или на байте, который может начинать конец строки.
        // Для UTF-8 такой байт (0xC2, 0xE2) чаще оказывается началом обычного символа - тогда поиск продолжается.
        int index = from;
        while (index < to) {
            int found = buf.forEachByte(index, to - index, stops.processorFor(this));
            if (found < 0) {
                return -1;
            }
            if (stops.contains(buf.getByte(found))) {
                return found;
            }
            if (isLineTerminator(buf, found, to)) {
                return -1;
            }
            index = found + 1;
        }
        return -1;
    }

    boolean isLineTerminator(ByteBuf buf, int index, int to) {
        byte value = buf.getByte(index);
        if (!terminatorLeads[value & 0xFF]) {
            return false;
        }
        if (!utf8 || value == '\n' || value == '\r') {
            return true;
        }
        if (value == UTF8_NEXT_LINE_LEAD) {
            return index + 1 < to && buf.getByte(index + 1) == UTF8_NEXT_LINE_TAIL;
        }
        if (index + 2 >= to || buf.getByte(index + 1) != UTF8_SEPARATOR_MIDDLE) {
            return false;
        }
        byte tail = buf.getByte(index + 2);
        return tail == UTF8_LINE_SEPARATOR_TAIL || tail == UTF8_PARAGRAPH_SEPARATOR_TAIL;
    }

    boolean isTerminatorLead(byte value) {
        return terminatorLeads[value & 0xFF];
    }

    private static boolean[] utf8TerminatorLeads() {
        boolean[] leads = new boolean[BYTE_VALUES];
        leads['\n'] = true;
        leads['\r'] = true;
        leads[UTF8_NEXT_LINE_LEAD & 0xFF] = true;
        leads[UTF8_SEPARATOR_LEAD & 0xFF] = true;

        return leads;
    }

    /**
     * Проверяет все 256 байтов кодировки: ASCII должны декодироваться в себя, остальные - в не-ASCII символы.
     * Заодно строится таблица байтов, означающих конец строки. Результат кэшируется по кодировке.
     */
    private static Optional<ByteTextSyntax> createSingleByteSyntax(Charset charset) {
        if (!charset.canEncode() || charset.newEncoder().maxBytesPerChar() != 1.0f) {
            return Optional.empty();
        }
        boolean[] terminators = new boolean[BYTE_VALUES];
        for (int value = 0; value < BYTE_VALUES; value++) {
            String decoded = new String(new byte[]{(byte) value}, charset);
            if (decoded.length() != 1 || !isCompatibleChar(value, decoded.charAt(0))) {
                return Optional.empty();
            }
            terminators[value] = isRegexLineTerminator(decoded.charAt(0));
        }
        return Optional.of(new ByteTextSyntax(charset, terminators, false));
    }

    private static boolean isCompatibleChar(int value, char decoded) {
        return value < 0x80
                ? decoded == value
                : decoded >= 0x80;
    }

    private static boolean isRegexLineTerminator(char value) {
        return value == '\n' || value == '\r' || value == '\u0085' || value == '\u2028' || value == '\u2029';
    }

    /**
     * Набор ASCII-байтов, на которых останавливается поиск в пределах строки.
     */
    static final class ByteStops {
        private final boolean[] stops = new boolean[BYTE_VALUES];
        private final Map<ByteTextSyntax, ByteProcessor> processors = new ConcurrentHashMap<>();

        ByteStops(char... values) {
            for (char value : values) {
                stops[value] = true;
            }
        }

        boolean contains(byte value) {
            return stops[value & 0xFF];
        }

        private ByteProcessor processorFor(ByteTextSyntax syntax) {
            return processors.computeIfAbsent(syntax, s -> value -> !contains(value) && !s.isTerminatorLead(value));
        }
    }
}
