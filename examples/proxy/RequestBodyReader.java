package com.otr.portal.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import lombok.experimental.UtilityClass;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * Читает тело запроса клиента в pooled direct-память кусками по 64 КиБ.
 * Память растёт только по мере прихода байтов: без предвыделения по Content-Length и без копирования при росте.
 *
 * @author alexraspopin
 * @since 01.10.2026
 */
@UtilityClass
class RequestBodyReader {
    static final int CHUNK_SIZE = 64 * 1024;

    /**
     * Дочитывает поток до конца, добавляя куски в {@code body}. Буфер должен допускать неограниченное число компонентов,
     * иначе {@link CompositeByteBuf} склеит их, скопировав тело целиком.
     * <p>
     * Массив {@code chunk} заполняется полностью перед созданием куска: Jetty отдаёт данные порциями по мере прихода
     * из сети, и без этого кусков было бы на порядки больше. {@code ByteBuf.writeBytes(InputStream, int)} не подходит:
     * для direct-буфера он на каждый вызов создаёт временный {@code byte[]} размером с кусок.
     */
    static void readBody(InputStream in, CompositeByteBuf body) throws IOException {
        byte[] chunk = new byte[CHUNK_SIZE];
        int read;
        while ((read = IOUtils.read(in, chunk)) > 0) {
            ByteBuf part = body.alloc().directBuffer(read);
            part.writeBytes(chunk, 0, read);
            body.addComponent(true, part);
        }
    }
}
