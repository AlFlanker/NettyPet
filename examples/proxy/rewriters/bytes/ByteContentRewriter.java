package com.otr.portal.proxy.rewriters.bytes;

import io.netty.buffer.ByteBuf;

/**
 * Перезапись тела ответа на уровне байтов без декодирования в {@link String}.
 * <p>
 * Реализация должна давать тот же результат, что и её {@link com.otr.portal.proxy.rewriters.ContentRewriter}.
 */
public interface ByteContentRewriter {

    /**
     * [Шаг 6.3] Вызывается из {@link ByteRewriteChain} по одному разу на переписчик.
     * Не освобождает {@code source} из {@link ByteRewriteContext}.
     *
     * @return тот же экземпляр {@code source}, если правок нет, иначе новый буфер
     */
    ByteBuf rewrite(ByteRewriteContext context);
}
