package dev.example.jettyclient.proxy;

/** Преобразование превысило заданный бюджет тела; engine возвращает upstream failure (502). */
public class ProxyBodyLimitException extends IllegalArgumentException {
    public ProxyBodyLimitException(String message) { super(message); }
}
