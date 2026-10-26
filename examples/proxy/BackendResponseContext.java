package com.otr.portal.proxy;

import com.otr.portal.hosts.Backend;
import com.otr.portal.proxy.headers.HeaderProcessor;
import com.otr.portal.proxy.logging.ProxiedRequestLifecycle;
import com.otr.portal.proxy.rewriters.ResponseBodyRewriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Builder;
import lombok.Value;
import reactor.core.publisher.MonoSink;

import java.util.Map;

/**
 * Всё, что нужно {@link BackendResponseHandler} для одной попытки проксирования.
 */
@Value
@Builder
class BackendResponseContext {
    HttpServletRequest request;
    HttpServletResponse response;
    Backend backend;
    ChannelManager channelManager;
    HeaderProcessor headerProcessor;
    ResponseBodyRewriter bodyRewriter;
    long maxRewriteBytes;
    ResponseOwnership ownership;
    ExchangeCompletion completion;
    ProxiedRequestLifecycle lifecycle;
    ProxyClientCallContext telemetry;
    Map<String, String> mdc;
    MonoSink<Integer> sink;
    String targetUrl;
    long startedAt;

    /**
     * Действия над сервлетным обменом, которые принадлежат {@link ProxyCommand}.
     */
    interface ExchangeCompletion {

        /**
         * Завершает асинхронную обработку запроса, если она была начата.
         */
        void completeExchange();

        /**
         * Заменяет ещё не отправленный ответ сообщением об ошибке подсистемы и завершает обмен.
         */
        void renderBackendFailure(Throwable cause);
    }
}
