package com.otr.portal.proxy;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;

/**
 * Транспорт Netty для соединений с бэкендами: группа event loop и класс канала одного транспорта.
 * <p>
 * На Linux используется epoll: нативный транспорт создаёт меньше мусора в heap и делает меньше системных вызовов,
 * чем NIO. Библиотека {@code netty-transport-native-epoll} для linux-x86_64 подключена в otrportal-core.
 * NIO остаётся запасным вариантом: другая ОС или архитектура, нативная библиотека не загрузилась
 * <i>Поддержку KQUEUE добавлять не стал т.к BSD/MacOs нет на стендах</i>
 */
enum NettyTransport {
    EPOLL {
        @Override
        EventLoopGroup createEventLoopGroup(int threads, ThreadFactory threadFactory) {
            return new EpollEventLoopGroup(threads, threadFactory);
        }

        @Override
        Class<? extends SocketChannel> socketChannelClass() {
            return EpollSocketChannel.class;
        }
    },
    NIO {
        @Override
        EventLoopGroup createEventLoopGroup(int threads, ThreadFactory threadFactory) {
            return new NioEventLoopGroup(threads, threadFactory);
        }

        @Override
        Class<? extends SocketChannel> socketChannelClass() {
            return NioSocketChannel.class;
        }
    };

    private static final Logger log = LoggerFactory.getLogger(NettyTransport.class);

    abstract EventLoopGroup createEventLoopGroup(int threads, ThreadFactory threadFactory);

    abstract Class<? extends SocketChannel> socketChannelClass();

    /**
     * Выбирает транспорт и пишет выбор в лог: на стенде по нему видно, загрузилась ли нативная библиотека.
     */
    static NettyTransport detect() {
        if (Epoll.isAvailable()) {
            log.info("Соединения с бэкендами: транспорт epoll");
            return EPOLL;
        }
        Throwable cause = Epoll.unavailabilityCause();
        if (log.isInfoEnabled()) {
            log.info("Соединения с бэкендами: транспорт NIO, epoll недоступен: {}", ExceptionUtils.getRootCauseMessage(cause));
        }
        log.debug("Причина недоступности epoll", cause);

        return NIO;
    }
}
