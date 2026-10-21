package dev.example.proxy.api;

/** Явный отказ Processor/политики назначения. Текст ошибки не раскрывается HTTP-клиенту. */
public final class ProxyRejection extends RuntimeException {
    private final int status;

    public ProxyRejection(int status, String message) {
        super(message);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Rejection status must be 400..599");
        }
        this.status = status;
    }

    public int status() { return status; }
}
