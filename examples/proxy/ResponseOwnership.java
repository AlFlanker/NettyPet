package com.otr.portal.proxy;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Арбитраж доступа к сервлетному ответу между обработчиком ответа бэкенда (event loop Netty)
 * и fallback-веткой Resilience4j({@link ProxyCommand} (поток таймера или поток ошибки).
 * <p>
 * Ответ достаётся тому, кто первым его захватил; второй участник ответ не трогает.
 * Без арбитража таймаут мог бы писать сообщение об ошибке в ответ, в который handler уже пишет тело.
 */
final class ResponseOwnership {
    private static final Object FALLBACK = new Object();

    private final AtomicReference<Object> owner = new AtomicReference<>();

    // [Шаг 5 - стриминг, 6.3 - перезапись]
    boolean claimForBackend(Object attempt) {
        return claim(attempt);
    }

    // [Ошибка 3]
    boolean claimForFallback() {
        return claim(FALLBACK);
    }

    boolean isOwnedBy(Object attempt) {
        return owner.get() == attempt;
    }

    private boolean claim(Object claimant) {
        return owner.compareAndSet(null, claimant)
                || owner.get() == claimant;
    }
}
