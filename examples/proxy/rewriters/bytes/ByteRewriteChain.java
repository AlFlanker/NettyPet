package com.otr.portal.proxy.rewriters.bytes;

import io.netty.buffer.ByteBuf;

import java.util.List;

/**
 * Последовательное применение {@link ByteContentRewriter} в порядке цепочки {@code ContentRewriter}.
 */
public final class ByteRewriteChain {
    private final List<ByteContentRewriter> rewriters;

    public ByteRewriteChain(List<ByteContentRewriter> rewriters) {
        this.rewriters = List.copyOf(rewriters);
    }

    /**
     * [Шаг 6.3] Забирает владение {@code source} из {@link ByteRewriteContext}.
     *
     * @return итоговое тело, которым владеет вызывающий
     */
    public ByteBuf rewrite(ByteRewriteContext context) {
        ByteBuf current = context.getSource();
        try {
            for (ByteContentRewriter rewriter : rewriters) {
                ByteBuf next = rewriter.rewrite(context.withSource(current));
                // Новый буфер держит retained-срезы предыдущего, поэтому предыдущий можно отпустить сразу:
                // память освободится, когда отпустят последний срез.
                if (next != current) {
                    current.release();
                    current = next;
                }
            }
            return current;
        } catch (RuntimeException e) {
            current.release();
            throw e;
        }
    }
}
