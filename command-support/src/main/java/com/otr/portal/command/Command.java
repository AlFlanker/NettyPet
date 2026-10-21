package com.otr.portal.command;

import reactor.core.publisher.Mono;

/**
 * Контракт команды, возвращающей один реактивный результат.
 *
 * @author alexraspopin
 * @since 04.02.2026
 */

public interface Command<T> {
    /**
     * Возвращает Mono выполнения команды. У стандартных реализаций работа начинается
     * при подписке; для каждой подписки заново проверяются политики допуска.
     */
    Mono<T> execute();
}
