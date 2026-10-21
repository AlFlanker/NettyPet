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
 * Ограниченные пулы HTTP/1.1: канал и обслуживаемый им обмен закреплены за одним циклом событий.
 * Класс управляет сетевыми ресурсами и их остановкой; маршрут выбирает вызывающий код.
 */
final class NettyTransport implements AutoCloseable {
    static final AttributeKey<ProxyExchange> EXCHANGE = AttributeKey.valueOf(NettyTransport.class, "exchange");
    private static final AttributeKey<io.netty.util.concurrent.ScheduledFuture<?>> IDLE =
            AttributeKey.valueOf(NettyTransport.class, "idle");
    private static final AttributeKey<Boolean> RETIRING = AttributeKey.valueOf(NettyTransport.class, "retiring");
    // Уведомление TLS close_notify может ещё отправляться при isActive()==true.
    // Закрывающийся канал уже нельзя выдавать из пула, хотя TCP-соединение ещё активно.
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
    // FixedChannelPool.close() закрывает свободные каналы, но не все занятые или подключающиеся.
    // Режим stayClosed также закроет канал, зарегистрированный после начала остановки.
    private final ChannelGroup channels = new DefaultChannelGroup(
            "proxy-upstreams", GlobalEventExecutor.INSTANCE, true);
    private final Semaphore admission;
    private final AtomicBoolean closed = new AtomicBoolean();
    private record PoolKey(Origin origin, EventLoop loop) { }

    NettyTransport(ProxyOptions config, DownstreamAborter downstreamAborter) throws Exception {
        this(config, downstreamAborter, ProxyIoAdapter.PORTABLE);
    }

    /** Создаёт общие DNS/TLS/I/O-ресурсы и лимит одновременных обменов для одного фасада. */
    NettyTransport(ProxyOptions config, DownstreamAborter downstreamAborter, ProxyIoAdapter ioAdapter) throws Exception {
        this.config = Objects.requireNonNull(config, "config");
        this.downstreamAborter = Objects.requireNonNull(downstreamAborter);
        this.ioAdapter = Objects.requireNonNull(ioAdapter, "ioAdapter");
        Set<Origin> origins = new HashSet<>();
        origins.add(Origin.from(config.upstream()));
        config.allowedOrigins().forEach(uri -> origins.add(Origin.from(uri)));
        allowed = Set.copyOf(origins);
        // Явно выбираем TLS-провайдер JDK: наличие tcnative не должно незаметно заменять
        // SSL-контекст нативным ресурсом, для которого нужен отдельный подсчёт ссылок.
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

    /** Проверяет флаг остановки и пытается без ожидания занять слот обмена. */
    public boolean tryAcquire() { return !closed.get() && admission.tryAcquire(); }
    public void releaseAdmission() { admission.release(); }
    public int activeRequests() { return config.maxConcurrent() - admission.availablePermits(); }
    public EventLoop nextLoop() { return loops.next(); }
    public boolean permits(Origin origin) { return allowed.contains(origin); }
    /** Регистрирует обмен для последующей остановки атомарно относительно close(). */
    synchronized boolean register(ProxyExchange exchange) {
        if (closed.get()) return false;
        active.add(exchange);
        return true;
    }
    void unregister(ProxyExchange exchange) { active.remove(exchange); }

    /** Возвращает ограниченный пул для разрешённого сервера и конкретного цикла событий. */
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

    /** Настраивает соединения, TLS и строгий HTTP-декодер; ограничивает очередь ожидающих выдачи канала. */
    private FixedChannelPool newPool(PoolKey key) {
        // AUTO_READ=false и одно чтение сокета за цикл ограничивают опережающее чтение.
        // Обмен запрашивает следующий read() после обработки текущих данных и готовности записи клиенту.
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
                // Строгая конфигурация не зависит от глобальных свойств Netty.
                // Неоднозначное сочетание Transfer-Encoding/Content-Length и повторные
                // Content-Length отклоняются до передачи заголовков клиенту. Объединение
                // всего тела и распаковка не включены: данные передаются потоком.
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

    /** Удаляет таймер простоя при выдаче, повторном возврате или окончательном закрытии канала. */
    private static void cancelIdleTimer(Channel channel) {
        var timer = channel.attr(IDLE).getAndSet(null);
        if (timer != null) timer.cancel(false);
    }

    /** Немедленно запрещает повторную выдачу канала и запускает его асинхронное закрытие. */
    private static void retire(Channel channel) {
        // Маркер ставится до close и не сбрасывается. Отмена таймера простоя при выдаче
        // канала предотвращает новое закрытие, но не отменяет уже начавшееся завершение TLS.
        channel.attr(RETIRING).set(Boolean.TRUE);
        channel.close();
    }

    /** Передаёт события текущему обмену и освобождает исходную ссылку на каждое принятое сообщение Netty. */
    private static final class Inbound extends ChannelInboundHandlerAdapter {
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            ProxyExchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange == null) {
                ReferenceCountUtil.release(message);
                retire(ctx.channel()); // Незапрошенные данные не должны попасть в следующий обмен этого пула.
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
     * Прекращает приём обменов и синхронно освобождает каналы, DNS и циклы событий.
     * Вызывается из потока жизненного цикла приложения: собственный поток I/O
     * или глобальный исполнитель Netty не может ждать ресурсы, которые сам должен освободить.
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
            // Сначала ставим очистку обменов в ещё работающие закреплённые циклы событий.
            // Закрытие пулов завершает ожидающие запросы каналов. Асинхронное закрытие
            // с awaitUninterruptibly не даёт прерыванию вызывающего потока оборвать очистку.
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
