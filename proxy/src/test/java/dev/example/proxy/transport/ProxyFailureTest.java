package dev.example.proxy.transport;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяет различение перегрузки, таймаутов и ошибок сети при выборе HTTP-статуса. */
class ProxyFailureTest {
    @Test
    void upstreamTimeoutsBecome504EvenWhenWrappedByNetworkLibraries() {
        for (Throwable timeout : List.of(new ConnectTimeoutException("connect"),
                new SslHandshakeTimeoutException("TLS"), new SocketTimeoutException("read"),
                new TimeoutException("deadline"))) {
            assertEquals(504, ProxyFailure.upstreamStatus(new IOException("Upstream failed", timeout)));
        }
        assertEquals(502, ProxyFailure.upstreamStatus(new ConnectException("Connection refused")));
        assertEquals(502, ProxyFailure.upstreamStatus(new IOException("Invalid upstream response")));
    }

    @Test
    void poolCapacityFailureIs503ButEstablishingAnUpstreamConnectionStillUses502Or504() {
        assertEquals(503, ProxyFailure.acquireStatus(new TimeoutException("No available slot")));
        assertEquals(503, ProxyFailure.acquireStatus(new IllegalStateException("Pending queue full or closed")));
        assertEquals(504, ProxyFailure.acquireStatus(new ConnectTimeoutException("connect")));
        assertEquals(504, ProxyFailure.acquireStatus(new IOException("TLS failed", new SslHandshakeTimeoutException("TLS"))));
        assertEquals(502, ProxyFailure.acquireStatus(new ConnectException("Connection refused")));
    }

    @Test
    void dnsQueryTimeoutWrappedAsResolutionFailureIs504ButUnknownHostIs502() {
        var timeout = new DnsNameResolverTimeoutException(
                new InetSocketAddress("127.0.0.1", 53),
                new DefaultDnsQuestion("upstream.example", DnsRecordType.A), "DNS query timed out");
        var resolutionFailure = new UnknownHostException("Failed to resolve upstream.example");
        resolutionFailure.initCause(timeout);
        var connectionFailure = new IOException("Unable to establish upstream connection", resolutionFailure);

        assertEquals(504, ProxyFailure.upstreamStatus(connectionFailure));
        assertEquals(504, ProxyFailure.acquireStatus(connectionFailure));
        assertEquals(502, ProxyFailure.acquireStatus(new UnknownHostException("NXDOMAIN")));
    }

    @Test
    void malformedCauseChainsCannotLoopForever() {
        IOException first = new IOException("first");
        IOException second = new IOException("second", first);
        first.initCause(second);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertEquals(502, ProxyFailure.upstreamStatus(first)));
        assertEquals(502, ProxyFailure.upstreamStatus(null));
    }
}
