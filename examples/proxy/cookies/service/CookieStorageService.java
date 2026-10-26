package com.otr.portal.proxy.cookies.service;

import com.otr.portal.core.session.v2.SessionDataStorage;
import com.otr.portal.core.session.v2.model.SessionKey;
import com.otr.portal.core.session.v2.model.SessionUpdateKey;
import com.otr.portal.hosts.Backend;
import com.otr.portal.proxy.cookies.Cookie;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static reactor.core.publisher.Flux.just;

/**
 * @author pobedenniy.alexey
 * @since 20.03.19
 */
@Service
public class CookieStorageService {
    public static final String SESSION_KEY = "backend-cookies";
    public static final int COOKIE_TIMEOUT = Integer.getInteger("backend.cookies.timeout", 60);
    private final SessionDataStorage sessionDataStorage;

    public CookieStorageService(SessionDataStorage sessionDataStorage) {
        this.sessionDataStorage = sessionDataStorage;
    }

    public Stream<Cookie> loadUserCookiesForBackend(String clientId, Backend backend) {
        SessionKey key = SessionKey.of(SESSION_KEY, clientId, backend.getName());
        Flux<Set<String>> savedCookies = sessionDataStorage.lookupValuesByKey(key);
        return savedCookies
                .flatMapIterable(e -> e)
                .map(Cookie::parse)
                .toStream();
    }

    public Stream<Cookie> addUserCookies(String clientId, Stream<Cookie> cookies, Backend backend) {
        SessionKey key = SessionKey.of(SESSION_KEY, clientId, backend.getName());
        final Flux<Set<String>> savedCookies = sessionDataStorage
                .lookupValuesByKey(key);
        savedCookies
                .switchIfEmpty(e -> just(new HashSet<>()))
                .single()
                .subscribe(h -> {
                            final List<String> newCookies = cookies.map(Cookie::toString).collect(Collectors.toList());
                            if (CollectionUtils.isNotEmpty(newCookies)) {
                                h.addAll(newCookies);
                                sessionDataStorage.saveValuesByKey(SessionUpdateKey.fromKey(key).expireAfterRead(Duration.ofMinutes(COOKIE_TIMEOUT)), h);
                            }
                        }
                );
        return Stream.empty();
    }
}
