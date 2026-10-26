package com.otr.portal.proxy.cookies;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class FakeCookieProcessor implements CookieProcessor {
    @Override
    public Stream<? extends Cookie> process(Stream<? extends Cookie> cookiesStream) {
        Map<String, Cookie> cookiesMap = cookiesStream.
                filter(el -> el != null && el.name() != null)
                .collect(Collectors.toMap(Cookie::name, cookie -> cookie));
        Cookie fakeCoockie = Cookie.builder().name("JSESSIONID").value("FakeJSESSIONID").build();
        cookiesMap.putIfAbsent("JSESSIONID", fakeCoockie);
        return cookiesMap.values().stream();
    }
}
