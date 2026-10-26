package dev.example.jettyclient.proxy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Неизменяемый набор read-only срезов тела без копирования байтов.
 * Позиции исходных буферов фиксируются при создании; backing memory остаётся общей
 * и не должна изменяться владельцем. Rewriter может вернуть срезы input: engine
 * удерживает input до завершения worker и всех записей output.
 * Для новых вставок разрешены только буферы без собственной pooled lifecycle;
 * этот контейнер не вызывает произвольный cleanup и не освобождает память сам.
 */
public final class ProxyResponseBody {
    static final int MAX_BUFFERS = 65_536;
    private final List<ByteBuffer> buffers;
    private final long length;

    private ProxyResponseBody(List<ByteBuffer> source) {
        Objects.requireNonNull(source, "buffers");
        int size = source.size();
        if (size > MAX_BUFFERS)
            throw new TooManyBuffersException("Response body exceeds buffer limit " + MAX_BUFFERS);
        List<ByteBuffer> snapshot = new ArrayList<>(size);
        long count = 0;
        for (ByteBuffer buffer : source) {
            Objects.requireNonNull(buffer, "buffer");
            ByteBuffer view = buffer.slice().asReadOnlyBuffer().order(buffer.order());
            count = Math.addExact(count, view.remaining());
            snapshot.add(view);
        }
        buffers = List.copyOf(snapshot);
        length = count;
    }

    public static ProxyResponseBody of(ByteBuffer buffer) {
        return new ProxyResponseBody(List.of(Objects.requireNonNull(buffer, "buffer")));
    }

    public static ProxyResponseBody of(List<ByteBuffer> buffers) { return new ProxyResponseBody(buffers); }

    public long length() { return length; }

    /** Число сегментов без создания дополнительных views. */
    public int bufferCount() { return buffers.size(); }

    /** Свежие read-only views: изменение их позиций не меняет этот объект. */
    public List<ByteBuffer> buffers() {
        return buffers.stream().map(buffer -> buffer.asReadOnlyBuffer().order(buffer.order())).toList();
    }

    /** Структура output превышает предел до выделения списка снимка. */
    public static final class TooManyBuffersException extends IllegalArgumentException {
        TooManyBuffersException(String message) { super(message); }
    }
}
