package dev.example.proxy.transport;

import dev.example.proxy.api.ProxyOptions;
import dev.example.proxy.core.Origin;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.pool.FixedChannelPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяем реальные I/O-потоки: mock не обнаружит ожидание потоком собственной остановки. */
@Timeout(15)
class TransportLifecycleTest {
    @Test
    void acquiringBeforeIdleExpiryCancelsTheTimerWithoutRetiringTheLease() throws Exception {
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            backend.setSoTimeout(3000);
            URI origin = new URI("http", null, backend.getInetAddress().getHostAddress(),
                    backend.getLocalPort(), null, null, null);
            try (NettyTransport transport = transport(origin, Duration.ofMillis(25))) {
                var loop = transport.nextLoop();
                FixedChannelPool pool = transport.pool(Origin.from(origin), loop);
                Channel first = pool.acquire().get(3, TimeUnit.SECONDS);
                HoldingClose delayedClose = new HoldingClose();
                try (Socket peer = backend.accept()) {
                    loop.submit(() -> first.pipeline().addLast("delayed-close", delayedClose))
                            .get(3, TimeUnit.SECONDS);
                    try {
                        Channel reused = loop.submit(() -> {
                            // Both operations run in one loop turn; the idle timer cannot run between them.
                            assertTrue(pool.release(first).isSuccess());
                            var acquisition = pool.acquire();
                            assertTrue(acquisition.isSuccess());
                            return acquisition.getNow();
                        }).get(3, TimeUnit.SECONDS);
                        loop.schedule(() -> { }, 75, TimeUnit.MILLISECONDS).get(3, TimeUnit.SECONDS);

                        assertSame(first, reused);
                        assertTrue(first.isActive());
                        assertEquals(1, delayedClose.started.getCount(), "Cancelled idle task must not start close");
                        assertEquals(1, pool.acquiredChannelCount());
                    } finally {
                        delayedClose.allowClose.set(true);
                        first.close().get(3, TimeUnit.SECONDS);
                        pool.release(first).get(3, TimeUnit.SECONDS);
                    }
                    assertEquals(0, pool.acquiredChannelCount());
                }
            }
        }
    }

    @Test
    void idleExpiryCannotLeaseAChannelWhoseCloseHasStartedButIsStillActive() throws Exception {
        try (ServerSocket backend = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            backend.setSoTimeout(3000);
            URI origin = new URI("http", null, backend.getInetAddress().getHostAddress(),
                    backend.getLocalPort(), null, null, null);
            try (NettyTransport transport = transport(origin, Duration.ofMillis(25))) {
                var loop = transport.nextLoop();
                FixedChannelPool pool = transport.pool(Origin.from(origin), loop);
                Channel first = pool.acquire().get(3, TimeUnit.SECONDS);
                HoldingClose delayedClose = new HoldingClose();
                loop.submit(() -> first.pipeline().addLast("delayed-close", delayedClose))
                        .get(3, TimeUnit.SECONDS);
                Channel replacement = null;
                try (Socket firstPeer = backend.accept()) {
                    firstPeer.setSoTimeout(3000);
                    try {
                        pool.release(first).get(3, TimeUnit.SECONDS);
                        assertTrue(delayedClose.started.await(3, TimeUnit.SECONDS));
                        // Models TLS close_notify: close started, but the socket remains ACTIVE.
                        assertTrue(first.isActive());
                        assertFalse(first.closeFuture().isDone());

                        replacement = pool.acquire().get(3, TimeUnit.SECONDS);

                        assertNotSame(first, replacement,
                                "A channel retiring after idle expiry must not be leased during asynchronous close");
                        assertTrue(replacement.isActive());
                        assertTrue(first.isActive(), "Rejection must not depend on physical close winning the race");
                        try (Socket replacementPeer = backend.accept()) {
                            assertEquals(1, pool.acquiredChannelCount());
                        }
                    } finally {
                        delayedClose.allowClose.set(true);
                        first.close().get(3, TimeUnit.SECONDS);
                        if (replacement != null) pool.release(replacement).get(3, TimeUnit.SECONDS);
                    }
                    assertEquals(-1, firstPeer.getInputStream().read());
                    assertEquals(0, pool.acquiredChannelCount());
                }
            }
        }
    }

    @Test
    void closeFromOwnerLoopFailsBeforeChangingTransportState() throws Exception {
        try (NettyTransport transport = transport(URI.create("http://127.0.0.1:1"))) {
            transport.nextLoop().submit(() -> {
                assertThrows(IllegalStateException.class, transport::close);
            }).get(3, TimeUnit.SECONDS);

            assertTrue(transport.tryAcquire(), "Rejected close must leave the transport usable");
            transport.releaseAdmission();
        }
    }

    @Test
    void shutdownClosesLeasedChannelsAndPendingAcquiresDespiteCallerInterrupt() throws Exception {
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            backend.setSoTimeout(3000);
            URI origin = new URI("http", null, backend.getInetAddress().getHostAddress(),
                    backend.getLocalPort(), null, null, null);
            try (NettyTransport transport = transport(origin)) {
                var loop = transport.nextLoop();
                FixedChannelPool pool = transport.pool(Origin.from(origin), loop);
                Channel leased = pool.acquire().get(3, TimeUnit.SECONDS);
                try (Socket peer = backend.accept()) {
                    peer.setSoTimeout(3000);
                    var waiting = pool.acquire();
                    loop.submit(() -> { }).get(3, TimeUnit.SECONDS);
                    assertFalse(waiting.isDone(), "One leased channel exhausts this pool");

                    Thread.currentThread().interrupt();
                    try {
                        transport.close();
                        assertTrue(Thread.currentThread().isInterrupted(), "Shutdown preserves interrupt status");
                    } finally {
                        Thread.interrupted(); // Не переносим interrupt в следующий JUnit test.
                    }

                    assertFalse(leased.isOpen());
                    assertTrue(waiting.isDone());
                    assertFalse(waiting.isSuccess());
                    assertFalse(transport.tryAcquire());
                    assertEquals(-1, peer.getInputStream().read(), "Shutdown must close the peer connection");
                }
            }
        }
    }

    private static NettyTransport transport(URI upstream) throws Exception {
        return transport(upstream, Duration.ofSeconds(30));
    }

    private static NettyTransport transport(URI upstream, Duration idleTimeout) throws Exception {
        ProxyOptions options = new ProxyOptions(upstream, 1, 4, 1, 1, 1024, 4096,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10),
                idleTimeout, List.of());
        return new NettyTransport(options, (request, failure) -> { });
    }

    private static final class HoldingClose extends ChannelOutboundHandlerAdapter {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean allowClose = new AtomicBoolean();

        @Override public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            if (allowClose.get()) {
                ctx.close(promise);
                return;
            }
            // Each intercepted close promise completes only when the real socket closes.
            ctx.channel().closeFuture().addListener(ignored -> promise.trySuccess());
            started.countDown();
        }
    }
}
