package dev.example.jettyclient.proxy;

import java.util.concurrent.CompletionStage;

/** Одна bounded загрузка без Servlet и без повторной отправки при ошибке. */
public interface ProxyFetchCall {
    /**
     * Ответ либо exceptional completion при сбое, отмене, deadline или лимите.
     * Handlers обязаны быть быстрыми: они могут выполняться на сетевом, timer или worker потоке.
     * Длительную работу подключайте через *Async со своим ограниченным executor.
     */
    CompletionStage<ProxyFetchResult> completion();
    void cancel(Throwable cause);
}
