package com.otr.portal.proxy;

import jakarta.servlet.http.Cookie;

import java.net.IDN;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * @author pobedenniy.alexey
 * @since 06.09.16
 */
public class CookieUtils {

    public static String generateHeader(Cookie cookie) {
        int version = cookie.getVersion();
        String value = cookie.getValue();
        String path = cookie.getPath();
        String domain = cookie.getDomain();

        StringBuilder buf = new StringBuilder(); // can't use StringBuilder due to DateFormat

        buf.append(cookie.getName());
        buf.append("=");
        buf.append(value);


        if (domain != null) {
            buf.append("; Domain=");
            buf.append(domain);
        }

        int maxAge = cookie.getMaxAge();
        if (maxAge >= 0) {
            if (version > 0) {
                buf.append("; Max-Age=");
                buf.append(maxAge);
            }
        }

        if (path != null) {
            buf.append("; Path=");
            buf.append(path);
        }

        if (cookie.getSecure()) {
            buf.append("; Secure");
        }

        if (cookie.isHttpOnly()) {
            buf.append("; HttpOnly");
        }
        return buf.toString();
    }


    public static int delimiterOffset(String input, int pos, int limit, char delimiter) {
        for (int i = pos; i < limit; i++) {
            if (input.charAt(i) == delimiter) {
                return i;
            }
        }
        return limit;
    }

    public static String trimSubstring(String string, int pos, int limit) {
        int start = skipLeadingAsciiWhitespace(string, pos, limit);
        int end = skipTrailingAsciiWhitespace(string, start, limit);
        return string.substring(start, end);
    }

    public static int skipTrailingAsciiWhitespace(String input, int pos, int limit) {
        for (int i = limit - 1; i >= pos; i--) {
            switch (input.charAt(i)) {
                case '\t':
                case '\n':
                case '\f':
                case '\r':
                case ' ':
                    continue;
                default:
                    return i + 1;
            }
        }
        return pos;
    }

    public static int skipLeadingAsciiWhitespace(String input, int pos, int limit) {
        for (int i = pos; i < limit; i++) {
            switch (input.charAt(i)) {
                case '\t':
                case '\n':
                case '\f':
                case '\r':
                case ' ':
                    continue;
                default:
                    return i;
            }
        }
        return limit;
    }

    public static String domainToAscii(String input) {
        try {
            String result = IDN.toASCII(input).toLowerCase(Locale.US);
            if (result.isEmpty()) {
                return null;
            }

            // Confirm that the IDN ToASCII result doesn't contain any illegal characters.
            if (containsInvalidHostnameAsciiCodes(result)) {
                return null;
            }
            // TODO: implement all label limits.
            return result;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean containsInvalidHostnameAsciiCodes(String hostnameAscii) {
        for (int i = 0; i < hostnameAscii.length(); i++) {
            char c = hostnameAscii.charAt(i);
            // The WHATWG Host parsing rules accepts some character codes which are invalid by
            // definition for OkHttp's host header checks (and the WHATWG Host syntax definition). Here
            // we rule out characters that would cause problems in host headers.
            if (c <= '\u001f' || c >= '\u007f') {
                return true;
            }
            // Check for the characters mentioned in the WHATWG Host parsing spec:
            // U+0000, U+0009, U+000A, U+000D, U+0020, "#", "%", "/", ":", "?", "@", "[", "\", and "]"
            // (excluding the characters covered above).А
            if (" #%/:?@[\\]".indexOf(c) != -1) {
                return true;
            }
        }
        return false;
    }

    public static final TimeZone UTC = TimeZone.getTimeZone("GMT");
    private static final ThreadLocal<DateFormat> STANDARD_DATE_FORMAT =
            new ThreadLocal<DateFormat>() {
                @Override
                protected DateFormat initialValue() {
                    // RFC 2616 specified: RFC 822, updated by RFC 1123 format with fixed GMT.
                    DateFormat rfc1123 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
                    rfc1123.setLenient(false);
                    rfc1123.setTimeZone(UTC);
                    return rfc1123;
                }
            };

    public static String format(Date value) {
        return STANDARD_DATE_FORMAT.get().format(value);
    }

    public static final long MAX_DATE = 253402300799999L;
}
