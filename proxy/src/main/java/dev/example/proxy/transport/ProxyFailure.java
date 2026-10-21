package dev.example.proxy.transport;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/** Разделяем недоступность/ошибку upstream, его timeout и локальную перегрузку. */
final class ProxyFailure {
    private ProxyFailure() { }

    static int upstreamStatus(Throwable failure) {
        // Ограниченный обход защищает даже от некорректной циклической цепочки causes.
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            // DNS timeout обычно вложен в UnknownHostException и не наследует
            // java.util.concurrent.TimeoutException; NXDOMAIN остаётся обычным 502.
            if (failure instanceof ConnectTimeoutException || failure instanceof SslHandshakeTimeoutException
                    || failure instanceof DnsNameResolverTimeoutException
                    || failure instanceof SocketTimeoutException || failure instanceof TimeoutException) return 504;
        }
        return 502;
    }

    static int acquireStatus(Throwable failure) {
        // FixedChannelPool использует TimeoutException для ожидания свободного слота,
        // IllegalStateException — для закрытого пула/переполнения его очереди.
        if (failure instanceof TimeoutException || failure instanceof IllegalStateException) return 503;
        return upstreamStatus(failure);
    }
}
