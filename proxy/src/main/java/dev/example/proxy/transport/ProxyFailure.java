package dev.example.proxy.transport;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/** Различает ошибку целевого сервера, истечение сетевого таймаута и локальную перегрузку. */
final class ProxyFailure {
    private ProxyFailure() { }

    /** Возвращает 504 для распознанного таймаута сети и 502 для остальных ошибок целевого сервера. */
    static int upstreamStatus(Throwable failure) {
        // Ограничение глубины не позволяет зациклиться на некорректной цепочке причин исключения.
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            // Таймаут DNS обычно вложен в UnknownHostException и не наследует
            // java.util.concurrent.TimeoutException; ответ NXDOMAIN по-прежнему даёт 502.
            if (failure instanceof ConnectTimeoutException || failure instanceof SslHandshakeTimeoutException
                    || failure instanceof DnsNameResolverTimeoutException
                    || failure instanceof SocketTimeoutException || failure instanceof TimeoutException) return 504;
        }
        return 502;
    }

    /** Отделяет перегрузку или закрытие пула (503) от ошибки подключения к целевому серверу. */
    static int acquireStatus(Throwable failure) {
        // FixedChannelPool использует TimeoutException для ожидания свободного слота,
        // IllegalStateException — для закрытого пула/переполнения его очереди.
        if (failure instanceof TimeoutException || failure instanceof IllegalStateException) return 503;
        return upstreamStatus(failure);
    }
}
