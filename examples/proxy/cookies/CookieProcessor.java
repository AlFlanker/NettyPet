package com.otr.portal.proxy.cookies;

import java.util.stream.Stream;

public interface CookieProcessor {
    default Stream<? extends Cookie> process(Stream<? extends Cookie> streamCookie) {
        return streamCookie;
    }
}
