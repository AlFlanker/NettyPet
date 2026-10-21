package dev.example.proxy.transport;

import java.util.List;

/** Netty и Servlet разбирают границы chunked, но не произвольное транспортное кодирование, например gzip. */
final class TransferCodings {
    private TransferCodings() { }

    /** Разрешает отсутствие Transfer-Encoding либо одно значение chunked без цепочки кодирований. */
    static boolean supported(List<String> fields) {
        // Единственная поддерживаемая цепочка — ровно один chunked; Content-Encoding
        // к этому ограничению отношения не имеет и передаётся вместе с исходными байтами.
        return fields.isEmpty() || fields.size() == 1 && fields.getFirst().trim().equalsIgnoreCase("chunked");
    }
}
