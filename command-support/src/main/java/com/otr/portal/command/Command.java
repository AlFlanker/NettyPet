package com.otr.portal.command;

import reactor.core.publisher.Mono;

/**
 * Общий интерфейс комманды
 *
 * @author alexraspopin
 * @since 04.02.2026
 */

public interface Command<T> {
    Mono<T> execute();
}
