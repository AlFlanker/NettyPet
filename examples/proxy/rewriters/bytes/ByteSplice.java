package com.otr.portal.proxy.rewriters.bytes;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;

/**
 * Сборка результата перезаписи без копирования неизменённых участков.
 * <p>
 * Результат — {@link CompositeByteBuf} из retained-срезов исходного тела и небольших
 * вставок в pooled direct-буферах. Правки добавляются слева направо.
 * <p>
 * Так замена 40 ссылок в теле 8 МиБ стоит нескольких сотен компонентов и нескольких КиБ вставок,
 * а не полной копии тела на каждое правило.
 */
final class ByteSplice {
    private static final int INSERT_CHUNK_SIZE = 4096;

    private final ByteBuf source;
    private final ByteBufAllocator allocator;
    private CompositeByteBuf output;
    private ByteBuf insertChunk;
    private int copiedUpTo;

    ByteSplice(ByteBuf source, ByteBufAllocator allocator) {
        this.source = source;
        this.allocator = allocator;
        this.copiedUpTo = source.readerIndex();
    }

    /**
     * Заменяет байты {@code [from, to)} исходного тела на {@code replacement}.
     */
    void replace(int from, int to, byte[] replacement) {
        copySource(from);
        appendInsert(replacement);
        copiedUpTo = to;
    }

    void insert(int at, byte[] insertion) {
        replace(at, at, insertion);
    }

    /**
     * @return исходный буфер, если правок не было, иначе новый буфер; исходный буфер не освобождается
     */
    ByteBuf finish() {
        if (output == null) {
            return source;
        }
        copySource(source.writerIndex());
        releaseInsertChunk();
        return output;
    }

    void discard() {
        releaseInsertChunk();
        if (output != null) {
            output.release();
            output = null;
        }
    }

    /**
     * Добавляет в результат неизменённый участок исходника {@code [copiedUpTo, to)} как срез, без копирования байтов.
     */
    private void copySource(int to) {
        int length = to - copiedUpTo;
        if (length <= 0) {
            return;
        }
        CompositeByteBuf target = output();
        // Срез composite вложил бы composite в composite; раскладка на исходные компоненты сохраняет структуру плоской.
        if (source instanceof CompositeByteBuf) {
            for (ByteBuf part : ((CompositeByteBuf) source).decompose(copiedUpTo, length)) {
                target.addComponent(true, part.retain());
            }
        } else {
            target.addComponent(true, source.retainedSlice(copiedUpTo, length));
        }
        copiedUpTo = to;
    }

    /**
     * Вставки складываются в общий буфер по 4 КиБ, а в результат попадают его срезами,
     * чтобы не выделять pooled-буфер на каждую короткую вставку.
     */
    private void appendInsert(byte[] bytes) {
        CompositeByteBuf target = output();
        if (bytes.length == 0) {
            return;
        }
        if (insertChunk == null || insertChunk.writableBytes() < bytes.length) {
            releaseInsertChunk();
            insertChunk = allocator.directBuffer(Math.max(INSERT_CHUNK_SIZE, bytes.length));
        }
        int start = insertChunk.writerIndex();
        insertChunk.writeBytes(bytes);
        target.addComponent(true, insertChunk.retainedSlice(start, bytes.length));
    }

    private CompositeByteBuf output() {
        if (output == null) {
            output = allocator.compositeDirectBuffer(Integer.MAX_VALUE);
        }
        return output;
    }

    private void releaseInsertChunk() {
        if (insertChunk != null) {
            insertChunk.release();
            insertChunk = null;
        }
    }
}
