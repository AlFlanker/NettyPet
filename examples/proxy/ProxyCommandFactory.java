package com.otr.portal.proxy;

import com.otr.portal.backends.ssl.netty.SslContextHandlerFactory;
import com.otr.portal.command.Command;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.core.Configuration;
import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.HostsConfiguration;
import com.otr.portal.hosts.URIOverride;
import com.otr.portal.metadata.MetadataLifecycleListener;
import com.otr.portal.proxy.headers.EnforceCachingProcessor;
import com.otr.portal.proxy.headers.HeaderProcessor;
import com.otr.portal.proxy.headers.PortalHeaderContributor;
import com.otr.portal.proxy.logging.ProxiedRequestLifecycle;
import com.otr.portal.proxy.rewriters.ContentRewriter;
import com.otr.portal.proxy.rewriters.ResponseBodyRewriter;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


/**
 * Фабрика {@link ProxyCommand} на базе Resilience4j.
 * <p>
 * Управляет кэшами {@link ChannelManager} и {@link Bootstrap},
 * создаёт команду проксирования с нужными свойствами.
 *
 * @author alexraspopin
 * @since 11.03.2026
 */
@Service
public class ProxyCommandFactory implements MetadataLifecycleListener {
    static final int MAX_INITIAL_LINE_LENGTH = 4096;
    static final int MAX_HEADER_SIZE = 8192;
    /**
     * Верхняя граница одного чтения из сокета и одного {@code HttpContent}. Каждое чтение превращается
     * в отдельную запись в Jetty со своими служебными объектами в heap, а direct-память обмена
     * ограничена одним чтением.
     */
    static final int MAX_READ_SIZE = 256 * 1024;
    private static final RecvByteBufAllocator READ_ALLOCATOR = new AdaptiveRecvByteBufAllocator(
            AdaptiveRecvByteBufAllocator.DEFAULT_MINIMUM, AdaptiveRecvByteBufAllocator.DEFAULT_INITIAL, MAX_READ_SIZE);

    private final ConcurrentMap<BackendInfo, ChannelManager> channelManagerCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChannelManager> channelManagerByBackendNameCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<BackendInfo, Bootstrap> bootstrapCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bootstrap> bootstrapByBackendNameCache = new ConcurrentHashMap<>();

    private final Configuration configuration;
    private final HeaderProcessor headerProcessor;
    private final CommandResilienceDecorator resilienceDecorator;
    private final ResponseBodyRewriter bodyRewriter;
    private final List<PortalHeaderContributor> enrichContributors;
    private final SslContextHandlerFactory sslContextHandlerFactory;
    private final OpenTelemetry openTelemetry;
    private final NettyTransport transport = NettyTransport.detect();

    public ProxyCommandFactory(Configuration configuration,
                               HeaderProcessor headerProcessor,
                               CommandResilienceDecorator resilienceDecorator,
                               @Autowired(required = false) List<ContentRewriter> contentRewriters,
                               List<PortalHeaderContributor> enrichContributors,
                               SslContextHandlerFactory sslContextHandlerFactory,
                               OpenTelemetry openTelemetry
    ) {
        this.configuration = configuration;
        this.headerProcessor = headerProcessor;
        this.resilienceDecorator = resilienceDecorator;
        this.enrichContributors = enrichContributors;
        this.sslContextHandlerFactory = sslContextHandlerFactory;
        this.openTelemetry = openTelemetry;
        this.bodyRewriter = new ResponseBodyRewriter(
                contentRewriters != null
                        ? contentRewriters
                        : Collections.emptyList(),
                configuration::isLegacyContentRewrite);
    }

    /**
     * [Шаг 1.1] Собирает команду для запроса. {@link ChannelManager} и {@link Bootstrap} бэкенда создаются
     * при первом запросе к нему и переиспользуются; таймаут и размер очереди берутся из настроек бэкенда.
     */
    public Command<Integer> createCommand(Backend backend,
                                          HttpServletRequest req,
                                          HttpServletResponse resp,
                                          @Nullable AsyncContext asyncContext,
                                          ProxiedRequestLifecycle lifecycle) {
        var bi = BackendInfo.fromBackend(backend);
        ChannelManager channelManager = channelManagerCache.computeIfAbsent(bi,
                this::createChannelManager);
        detectBackendChangeAndCloseChannelManager(backend, channelManager);

        HeaderProcessor processor = this.headerProcessor;
        if (ProxyRequestClassifier.classify(req.getServletPath()).equals(ProxyRequestClassifier.STATIC)) {
            processor = new EnforceCachingProcessor(configuration.getCachePeriod(), headerProcessor);
        }

        final Optional<URIOverride> uriOverride = backend.getUriOverride(req.getServletPath());
        int timeout = uriOverride.map(URIOverride::getTimeout)
                .orElse(backend.getTimeout());

        var commandProperties = CommandProperties.builder()
                .timeout(timeout > 0
                        ? Duration.ofMillis(timeout)
                        : Duration.ZERO
                )
                .maxConcurrency(backend.getQueueSize())
                .build();

        var exchange = ProxyCommand.ServletExchange.builder()
                .request(req)
                .response(resp)
                .asyncContext(asyncContext)
                .build();

        var backendContext = ProxyCommand.BackendContext.builder()
                .backend(backend)
                .channelManager(channelManager)
                .maxContentLengthMb(configuration.getMaxContentLengthMb())
                .build();

        var contentPipeline = ProxyCommand.ContentPipeline.builder()
                .headerProcessor(processor)
                .bodyRewriter(bodyRewriter)
                .enrichContributors(enrichContributors)
                .build();

        var tracer = openTelemetry.getTracer(ProxyCommand.class.getPackageName());

        return ProxyCommand.builder()
                .resilienceDecorator(resilienceDecorator)
                .commandProperties(commandProperties)
                .exchange(exchange)
                .backendContext(backendContext)
                .pipeline(contentPipeline)
                .authentication(SecurityContextHolder.getContext().getAuthentication())
                .lifecycle(lifecycle)
                .tracer(tracer)
                .build();
    }

    private void detectBackendChangeAndCloseChannelManager(Backend backend, ChannelManager channelManager) {
        ChannelManager oldChannelManager = channelManagerByBackendNameCache.get(backend.name());
        if (oldChannelManager != null && oldChannelManager != channelManager) {
            oldChannelManager.destroy();
        }
    }

    private ChannelManager createChannelManager(BackendInfo backend) {
        final Bootstrap bootstrap =
                bootstrapCache.computeIfAbsent(backend, this::createNetty);
        detectBackendChangeAndCloseBootStrap(backend, bootstrap);
        final ChannelManager channelManager = new ChannelManager(backend, bootstrap, configuration.isKeepAlive());
        channelManagerByBackendNameCache.put(backend.name(), channelManager);
        return channelManager;
    }

    private void detectBackendChangeAndCloseBootStrap(BackendInfo backend, Bootstrap bootstrap) {
        final Bootstrap oldBoostrap = bootstrapByBackendNameCache.get(backend.name());
        if (oldBoostrap != null && oldBoostrap != bootstrap) {
            oldBoostrap.config().group().shutdownGracefully();
        }
    }

    private Bootstrap createNetty(final BackendInfo backend) {
        EventLoopGroup loopGroup = transport.createEventLoopGroup(backend.poolSize(),
                new CustomizableThreadFactory("NettyWorker." + backend.name()));
        InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        Bootstrap bootstrap = createBootstrap(backend, loopGroup);
        bootstrapByBackendNameCache.put(backend.name(), bootstrap);

        return bootstrap;
    }

    private Bootstrap createBootstrap(BackendInfo backend, EventLoopGroup loopGroup) {
        var initializer = ProxyChannelInitializer.builder()
                .sslContextHandlerFactory(this.sslContextHandlerFactory)
                .backend(backend)
                .build();
        return new Bootstrap()
                .group(loopGroup)
                .channel(transport.socketChannelClass())
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(8 * 1024, 32 * 1024))
                // Тела ответов читаются в pooled direct-буферы: вне heap и без аллокации на каждое чтение.
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.RCVBUF_ALLOCATOR, READ_ALLOCATOR)
                .handler(initializer);
    }

    @Override
    public <T> void configurationUpdated(T configuration) {
        if (configuration instanceof HostsConfiguration) {
            channelManagerCache.clear();
        }
    }
}
