package com.otr.portal.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOutboundInvoker;
import io.netty.handler.ssl.SslHandler;
import lombok.Getter;

import java.net.URI;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author pobedenniy.alexey
 * @since 20.04.18
 */
public class ChannelManager {
    private final Bootstrap bootstrap;
    private final String targetHost;
    private final int targetPort;
    @Getter
    private final boolean keepAlive;
    private final ConcurrentLinkedDeque<Channel> channels = new ConcurrentLinkedDeque<>();

    public ChannelManager(BackendInfo backend, Bootstrap bootstrap, boolean keepAlive) {
        this.keepAlive = keepAlive;
        String targetUrl = backend.fullBackendUrl();
        URI parsedURI = URI.create(targetUrl);
        this.bootstrap = bootstrap;
        this.targetHost = parsedURI.getHost();
        this.targetPort = getPort(parsedURI);
    }

    /**
     * [Шаг 3] Выдаёт канал к бэкенду: из пула keep-alive, если он включён и там есть живой канал, иначе новое соединение.
     * <p>
     * {@code channelConsumer} (шаг 4) вызывается в event loop канала, {@code fail} — [Ошибка 3].
     */
    public void connect(Consumer<Channel> channelConsumer, Supplier<Void> fail) {
        if (keepAlive) {
            Channel channel = channels.poll();
            if (channel != null && channel.isActive()) {
                    // [Шаг 3.1] Канал из пула.
                    // Состояние обмена меняется только в event loop канала,
                    // а connect вызывается из потока, запустившего команду.
                    channel.eventLoop().execute(() ->
                            channelConsumer.accept(channel));
                    return;
                }

        }
        newConnection(channelConsumer, fail);
    }

    // [Шаг 3.2] Новое соединение: pipeline собирает initChannel из ProxyCommandFactory, затем TLS-рукопожатие.
    private void newConnection(Consumer<Channel> channelConsumer, Supplier<Void> fail) {
        bootstrap.connect(targetHost, targetPort)
                .addListener((ChannelFutureListener) future -> {
            final Channel channel = future.channel();
            if (!future.isSuccess()) {
                if (channel != null) {
                    channel.close();
                }
                fail.get();
                return;
            }
            // "рукопожатие"
            SslHandler ssl = channel.pipeline().get(SslHandler.class);
            if (ssl != null) {
                ssl.handshakeFuture().addListener(h -> {
                    if (h.isSuccess()) {
                        channelConsumer.accept(channel);
                    }
                    else {
                        channel.close();
                        fail.get();
                    }
                });
            } else {
                channelConsumer.accept(channel);
            }
        });
    }

    private int getPort(URI uri) {
        int port = uri.getPort();
        if (port == -1) {
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                port = 80;
            } else if ("https".equalsIgnoreCase(uri.getScheme())) {
                port = 443;
            }
        }
        return port;
    }


    /**
     * [Шаг 8.1] Возвращает канал в пул keep-alive или закрывает его.
     * В пуле канал читает сам, чтобы сразу заметить закрытие соединения бэкендом.
     */
    public void close(Channel channel) {
        if (keepAlive && channel.isActive()) {
            channel.config().setAutoRead(true);
            channels.offer(channel);
        } else {
            channel.close();
        }
    }

    public void destroy() {
        channels.forEach(ChannelOutboundInvoker::close);
    }
}
