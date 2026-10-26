package com.otr.portal.proxy;

import com.otr.portal.backends.ssl.netty.SslContextHandlerFactory;
import com.otr.portal.backends.ssl.netty.SslHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;

import static com.otr.portal.proxy.ProxyCommandFactory.MAX_HEADER_SIZE;
import static com.otr.portal.proxy.ProxyCommandFactory.MAX_INITIAL_LINE_LENGTH;
import static com.otr.portal.proxy.ProxyCommandFactory.MAX_READ_SIZE;

/**
 * Вызывается для каждого нового соединения, до TLS-рукопожатия. Задаёт базовый pipeline
 */
@Value
@Builder
@EqualsAndHashCode(callSuper = true)
class ProxyChannelInitializer extends ChannelInitializer<SocketChannel> {
    BackendInfo backend;
    SslContextHandlerFactory sslContextHandlerFactory;

    @Override
    protected void initChannel(SocketChannel channel) {
        SocketChannelConfig channelConfig = channel
                .config()
                .setTcpNoDelay(true);
        if (backend.timeout() > 0) {
            channelConfig.setConnectTimeoutMillis(backend.timeout());
        }
        ChannelPipeline pipeline = channel.pipeline();
        // mTLS/HTTPS
        sslContextHandlerFactory.createSslHandler(SslHandlerContext.of(channel, backend))
                .ifPresent(sslHandler ->
                        pipeline.addLast("ssl", sslHandler));
        // Агрегатора нет намеренно: тело не собирается в памяти целиком, а если его нужно
        // переписать, накоплением занимается BackendResponseHandler.
        pipeline.addLast("codec", new HttpClientCodec(MAX_INITIAL_LINE_LENGTH, MAX_HEADER_SIZE, MAX_READ_SIZE));
        // Одно сообщение на read(): кодек может разобрать одно чтение из сокета в несколько HttpObject,
        // а BackendResponseHandler держит в записи клиенту не больше одного буфера.
        pipeline.addLast("flow", new FlowControlHandler());
        // Потоковая отправка multipart-тела запроса (HttpPostRequestEncoder).
        pipeline.addLast("chunks", new ChunkedWriteHandler()); // ДО  flow ?
    }
}
