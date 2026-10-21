package dev.example.proxy.transport;

import dev.example.proxy.api.ProxyOptions;
import dev.example.proxy.core.Origin;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.pool.*;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLParameters;

/**
 * Ограниченные HTTP/1.1-пулы: один канал и его exchange всегда принадлежат одному event loop.
 * Здесь находятся сетевые ресурсы и их жизненный цикл; маршрутизацию определяет вызывающий код.
 */
final class NettyTransport implements AutoCloseable {
    static final AttributeKey<ProxyExchange> EXCHANGE = AttributeKey.valueOf(NettyTransport.class, "exchange");
    private static final AttributeKey<io.netty.util.concurrent.ScheduledFuture<?>> IDLE =
            AttributeKey.valueOf(NettyTransport.class, "idle");
    private static final AttributeKey<Boolean> RETIRING = AttributeKey.valueOf(NettyTransport.class, "retiring");
    // TLS close_notify может ещё flush-иться при isActive()==true. Такой канал уже
    // нельзя выдавать из idle pool, даже если физическое закрытие ещё не завершилось.
    private static final ChannelHealthChecker REUSABLE = channel -> channel.eventLoop().newSucceededFuture(
            channel.isActive() && channel.attr(RETIRING).get() != Boolean.TRUE);
    private final ProxyOptions config;
    private final DownstreamAborter downstreamAborter;
    private final ProxyIoAdapter ioAdapter;
    private final EventLoopGroup loops;
    private final DnsAddressResolverGroup resolver;
    private final SslContext tls;
    private final Set<Origin> allowed;
    private final Map<PoolKey, FixedChannelPool> pools = new ConcurrentHashMap<>();
    private final Set<ProxyExchange> active = ConcurrentHashMap.newKeySet();
    // FixedChannelPool.close() закрывает свободные каналы, но не все выданные/подключающиеся.
    // stayClosed также закроет канал, чья регистрация завершится уже после начала shutdown.
    private final ChannelGroup channels = new DefaultChannelGroup(
            "proxy-upstreams", GlobalEventExecutor.INSTANCE, true);
    private final Semaphore admission;
    private final AtomicBoolean closed = new AtomicBoolean();
    private record PoolKey(Origin origin, EventLoop loop) { }

    NettyTransport(ProxyOptions config, DownstreamAborter downstreamAborter) throws Exception {
        this(config, downstreamAborter, ProxyIoAdapter.PORTABLE);
    }

    NettyTransport(ProxyOptions config, DownstreamAborter downstreamAborter, ProxyIoAdapter ioAdapter) throws Exception {
        this.config = Objects.requireNonNull(config, "config");
        this.downstreamAborter = Objects.requireNonNull(downstreamAborter);
        this.ioAdapter = Objects.requireNonNull(ioAdapter, "ioAdapter");
        Set<Origin> origins = new HashSet<>();
        origins.add(Origin.from(config.upstream()));
        config.allowedOrigins().forEach(uri -> origins.add(Origin.from(uri)));
        allowed = Set.copyOf(origins);
        // Фиксируем JDK provider: наличие tcnative в приложении не должно незаметно менять
        // владение SSL-контекстом на reference-counted native resource.
        this.tls = SslContextBuilder.forClient().sslProvider(SslProvider.JDK)
                .protocols("TLSv1.3", "TLSv1.2").build();
        this.resolver = new DnsAddressResolverGroup(new DnsNameResolverBuilder()
                .datagramChannelType(NioDatagramChannel.class)
                .socketChannelType(NioSocketChannel.class)
                .nameServerProvider(DnsServerAddressStreamProviders.platformDefault())
                .ttl(0, 60).negativeTtl(5)
                .queryTimeoutMillis(config.connectTimeout().toMillis())
                .maxQueriesPerResolve(16));
        this.loops = new MultiThreadIoEventLoopGroup(config.ioThreads(),
                new DefaultThreadFactory("proxy-io"), NioIoHandler.newFactory());
        admission = new Semaphore(config.maxConcurrent());
    }

    void abortDownstream(jakarta.servlet.http.HttpServletRequest request, Throwable failure) {
        downstreamAborter.abort(request, failure);
    }

    boolean supportsDirectInput(jakarta.servlet.ServletInputStream input) {
        return ioAdapter.supportsDirectInput(input);
    }

    DownstreamWriter createWriter(jakarta.servlet.http.HttpServletRequest request,
                                  jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        return ioAdapter.createWriter(request, response);
    }

    public boolean tryAcquire() { return !closed.get() && admission.tryAcquire(); }
    public void releaseAdmission() { admission.release(); }
    public int activeRequests() { return config.maxConcurrent() - admission.availablePermits(); }
    public EventLoop nextLoop() { return loops.next(); }
    public boolean permits(Origin origin) { return allowed.contains(origin); }
    synchronized boolean register(ProxyExchange exchange) {
        if (closed.get()) return false;
        active.add(exchange);
        return true;
    }
    void unregister(ProxyExchange exchange) { active.remove(exchange); }

    FixedChannelPool pool(Origin origin, EventLoop loop) {
        if (closed.get()) throw new IllegalStateException("Transport closed");
        if (!permits(origin)) throw new IllegalArgumentException("Upstream origin not allowed");
        PoolKey key = new PoolKey(origin, loop);
        FixedChannelPool existing = pools.get(key);
        if (existing != null) return existing;
        synchronized (this) {
            if (closed.get()) throw new IllegalStateException("Transport closed");
            return pools.computeIfAbsent(key, this::newPool);
        }
    }

    private FixedChannelPool newPool(PoolKey key) {
        // AUTO_READ=false и один socket-read за цикл ограничивают опережающее чтение:
        // следующий read() выдаёт exchange только после освобождения downstream-буфера.
        FixedRecvByteBufAllocator recv = new FixedRecvByteBufAllocator(config.chunkBytes());
        recv.maxMessagesPerRead(1);
        Bootstrap bootstrap = new Bootstrap().group(key.loop()).channel(NioSocketChannel.class)
                .resolver(resolver).remoteAddress(key.origin().host(), key.origin().port())
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.RCVBUF_ALLOCATOR, recv)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.connectTimeout().toMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(config.chunkBytes(), config.chunkBytes() * 2));
        return new FixedChannelPool(bootstrap, new AbstractChannelPoolHandler() {
            @Override public void channelCreated(Channel channel) {
                channel.closeFuture().addListener(ignored -> cancelIdleTimer(channel));
                channels.add(channel);
                if (key.origin().scheme().equals("https")) {
                    SslHandler ssl = tls.newHandler(channel.alloc(), key.origin().host(), key.origin().port());
                    SSLParameters parameters = ssl.engine().getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.engine().setSSLParameters(parameters);
                    ssl.setHandshakeTimeoutMillis(config.connectTimeout().toMillis());
                    channel.pipeline().addLast("tls", ssl);
                }
                // Явная строгая конфигурация не зависит от process-wide Netty properties.
                // В частности, неоднозначные TE+CL и повторные CL отклоняются до передачи
                // заголовков клиенту. Aggregator/decompressor отсутствуют: тело остаётся потоком.
                HttpDecoderConfig decoder = new HttpDecoderConfig()
                        .setMaxInitialLineLength(8192).setMaxHeaderSize(16384)
                        .setMaxChunkSize(config.chunkBytes()).setValidateHeaders(true)
                        .setAllowDuplicateContentLengths(false).setStrictLineParsing(true)
                        .setUseRfc9112TransferEncoding(true);
                channel.pipeline().addLast("http", new HttpClientCodec(decoder, false, true));
                channel.pipeline().addLast("bridge", new Inbound());
            }
            @Override public void channelAcquired(Channel channel) {
                cancelIdleTimer(channel);
            }
            @Override public void channelReleased(Channel channel) {
                cancelIdleTimer(channel);
                if (closed.get()) { retire(channel); return; }
                if (channel.isActive()) channel.attr(IDLE).set(channel.eventLoop().schedule(
                        () -> retire(channel), config.idleConnectionTimeout().toMillis(), TimeUnit.MILLISECONDS));
            }
        }, REUSABLE, FixedChannelPool.AcquireTimeoutAction.FAIL,
                config.acquireTimeout().toMillis(), config.connectionsPerOriginPerLoop(),
                config.pendingAcquiresPerPool());
    }

    private static void cancelIdleTimer(Channel channel) {
        var timer = channel.attr(IDLE).getAndSet(null);
        if (timer != null) timer.cancel(false);
    }

    private static void retire(Channel channel) {
        // Маркер ставится ДО close и никогда не сбрасывается: отмена idle timer при
        // acquire предотвращает только ещё не начатое закрытие, но не отменяет TLS shutdown.
        channel.attr(RETIRING).set(Boolean.TRUE);
        channel.close();
    }

    private static final class Inbound extends ChannelInboundHandlerAdapter {
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            ProxyExchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange == null) {
                ReferenceCountUtil.release(message);
                retire(ctx.channel()); // Незапрошенные данные не должны попасть в следующий exchange этого пула.
                return;
            }
            try { exchange.received(message); }
            catch (Throwable failure) { exchange.fail(502, failure); }
            finally { ReferenceCountUtil.release(message); }
        }
        @Override public void channelReadComplete(ChannelHandlerContext ctx) {
            ProxyExchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange != null) exchange.readComplete();
        }
        @Override public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            ProxyExchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange != null) exchange.signal();
        }
        @Override public void channelInactive(ChannelHandlerContext ctx) {
            ProxyExchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange != null) exchange.upstreamClosed();
        }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ProxyExchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange != null) exchange.fail(ProxyFailure.upstreamStatus(cause), cause);
            else retire(ctx.channel());
        }
    }

    /**
     * Синхронная остановка из lifecycle-потока приложения. На собственном I/O-потоке
     * ждать остановки группы нельзя: этот же поток нужен для освобождения каналов.
     */
    @Override public void close() {
        for (var executor : loops) {
            if (executor.inEventLoop())
                throw new IllegalStateException("Close proxy from an application lifecycle thread, not its I/O loop");
        }
        if (GlobalEventExecutor.INSTANCE.inEventLoop())
            throw new IllegalStateException("Close proxy outside Netty's global executor");
        List<ProxyExchange> closing;
        List<FixedChannelPool> closingPools;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            closing = List.copyOf(active);
            closingPools = List.copyOf(pools.values());
        }
        try {
            // Сначала ставим cleanup exchange в ещё работающие owner loops. Закрытие пулов
            // завершает pending acquire; используем async+awaitUninterruptibly, чтобы interrupt
            // lifecycle-потока не оборвал освобождение остальных сетевых ресурсов.
            closing.forEach(ProxyExchange::shutdown);
            closingPools.forEach(pool -> pool.closeAsync().awaitUninterruptibly());
        } finally {
            try {
                channels.close().awaitUninterruptibly();
            } finally {
                try { resolver.close(); }
                finally {
                    loops.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
                    pools.clear();
                }
            }
        }
    }
}
