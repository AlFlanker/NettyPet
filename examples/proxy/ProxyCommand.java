package com.otr.portal.proxy;

import com.otr.portal.command.AbstractResilienceCommand;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.hosts.Backend;
import com.otr.portal.proxy.exceptions.BackendTimeoutException;
import com.otr.portal.proxy.headers.HeaderProcessor;
import com.otr.portal.proxy.headers.PortalHeaderContributor;
import com.otr.portal.proxy.logging.ProxiedRequestLifecycle;
import com.otr.portal.proxy.rewriters.ResponseBodyRewriter;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.opentelemetry.api.trace.Tracer;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.ofNullable;

/**
 * Команда проксирования к конкретному бэкэнду.
 * <p>
 * Resilience4j-обвязка (TimeLimiter, Bulkhead, CircuitBreaker, Retry)
 * применяется через {@link AbstractResilienceCommand}.
 * Ответ бэкенда передаётся клиенту через {@link BackendResponseHandler};
 * сервлетный ответ достаётся либо ему, либо fallback-ветке (см. {@link ResponseOwnership}).
 * <p>
 * Порядок вызовов. Метки {@code [Шаг N]} и {@code [Ошибка N]} в комментариях классов проксирования
 * ссылаются на этот список.
 * <ol>
 *     <li>[Шаг 1] {@link NettyProxyRequestExecutor} в потоке сервлета создаёт команду
 *     ({@link ProxyCommandFactory#createCommand}, шаг 1.1) и подписывается на её результат.</li>
 *     <li>[Шаг 2] {@code execute()} оборачивает {@link #doExecute()} в Resilience4j; при подписке
 *     {@link #innerInvoke} (шаг 2.1) строит запрос и {@link BackendResponseHandler} и запрашивает канал.</li>
 *     <li>[Шаг 3] {@link ChannelManager#connect}: канал из пула keep-alive (3.1) или новое соединение (3.2)
 *     с pipeline из {@code ProxyCommandFactory} и TLS-рукопожатием.</li>
 *     <li>[Шаг 4] {@link #attachHandler} в event loop канала: handler добавляется в pipeline и выключает
 *     {@code autoRead} (4.1), запрос уходит бэкенду (4.2), запрашивается первое чтение (4.3).</li>
 *     <li>[Шаг 5] {@code BackendResponseHandler.receiveHead}: заголовки ответа и выбор пути - стриминг или перезапись.</li>
 *     <li>[Шаг 6] Тело читается по одному чанку на чтение из сокета:
 *     6.1 - стриминг, чанк сразу уходит во writer;
 *     6.2 - перезапись, чанк добавляется к накопленному телу и запрашивается следующий;
 *     6.3 - перезапись, после последнего чанка тело переписывает {@link ResponseBodyRewriter}
 *     и оно уходит во writer одним буфером.</li>
 *     <li>[Шаг 7] Запись клиенту: writer создаётся при первой записи (7.1), принимает буфер (7.2),
 *     пишет его в Jetty по мере готовности клиента (7.3) и сообщает {@code onWritable} (7.4).
 *     Только после этого handler запрашивает следующий чанк - возврат к шагу 6.</li>
 *     <li>[Шаг 8] Завершение: после последнего чанка канал сразу возвращается в пул (8.1), writer дописывает тело
 *     и сообщает {@code onCompleted} (8.2), handler завершает async-обработку запроса и отдаёт код ответа
 *     в {@code Mono} — его получает подписчик шага 1 (8.3).</li>
 * </ol>
 * Ветки ошибок:
 * <ul>
 *     <li>[Ошибка 1] {@code BackendResponseHandler.failExchange}: сбой отправки запроса, обрыв или ошибка канала,
 *     превышение лимита тела. Канал закрывается; если ответ уже захвачен handler'ом, он оформляется там же.
 *     Затем {@code sink.error} — ошибка 3. Retry срабатывает только на {@code RetryableException},
 *     которую прокси не бросает, поэтому повтора с шага 2 нет.</li>
 *     <li>[Ошибка 2] Таймаут TimeLimiter: отмена подписки вызывает {@code BackendResponseHandler.cancel} (дальше — ошибка 1),
 *     и одновременно таймаут уходит в fallback (ошибка 3). Ответ пишет тот, кто первым его захватит.</li>
 *     <li>[Ошибка 3] {@link #fallbackOnError}: сообщение об ошибке клиенту, если ответ ещё не занят handler'ом.
 *     Сюда же приходит неудачное подключение из шага 3.</li>
 *     <li>[Ошибка 4] {@code BackendResponseHandler.onFailed}: клиент перестал принимать ответ. Канал закрывается,
 *     обмен завершается успешно.</li>
 * </ul>
 *
 * @author alexraspopin
 * @since 10.03.2026
 */
@Slf4j
@Value
@EqualsAndHashCode(callSuper = false)
public class ProxyCommand extends AbstractResilienceCommand<Integer> {
    public static final String COMMAND_NAME_PREFIX = "proxyRequest_";
    private static final String BACKEND_FAILURE_MESSAGE = "При запросе данных подсистемы произошла ошибка";
    private static final String BACKEND_UNAVAILABLE_MESSAGE = "Запрашиваемая подсистема недоступна";

    ServletExchange exchange;
    BackendContext backendContext;
    ContentPipeline pipeline;
    Authentication authentication;
    ProxiedRequestLifecycle lifecycle;
    Tracer tracer;
    String targetUrl;
    AtomicLong startedAt = new AtomicLong();
    ResponseOwnership ownership = new ResponseOwnership();

    @Builder
    private ProxyCommand(
            CommandResilienceDecorator resilienceDecorator,
            CommandProperties commandProperties,
            ServletExchange exchange,
            BackendContext backendContext,
            ContentPipeline pipeline,
            Authentication authentication,
            ProxiedRequestLifecycle lifecycle,
            Tracer tracer
    ) {
        super(resilienceDecorator, commandProperties);
        this.exchange = exchange;
        this.backendContext = backendContext;
        this.pipeline = pipeline;
        this.authentication = authentication;
        this.lifecycle = lifecycle;
        this.tracer = tracer;

        this.targetUrl = resolveTargetUrl();
        this.lifecycle.setBackendName(backendContext.backend.getName());
        this.lifecycle.setTargetUrl(targetUrl);
    }

    @Override
    protected String getCommandName() {
        return COMMAND_NAME_PREFIX + backendContext.backend.getName();
    }

    /**
     * [Шаг 2] Вызывается из {@code execute()} до декорирования; {@link #innerInvoke} выполняется при подписке
     * на результат.
     */
    @Override
    protected Mono<Integer> doExecute() {
        // Полностью ручное управление потоком из-за Netty
        return Mono.create(this::innerInvoke);
    }

    /**
     * [Ошибка 3] Ответ клиенту при ошибке или таймауте. Пишется, только если {@link BackendResponseHandler}
     * ещё не начал формировать ответ сам (см. {@link ResponseOwnership}).
     */
    @Override
    protected Mono<Integer> fallbackOnError(Throwable e) {
        if (isTimeoutLike(e)) {
            return Mono.fromCallable(this::timeoutRequest);
        }
        return Mono.fromCallable(() -> failRequest(e));
    }

    /**
     * [Шаг 2.1] Запускает один обмен с бэкендом: строит запрос, создаёт обработчик ответа и получает канал.
     * Дальше всё происходит асинхронно в event loop канала; результат (HTTP-код) или ошибку
     * {@link BackendResponseHandler} отдаёт в {@code sink}.
     */
    private void innerInvoke(MonoSink<Integer> sink) {
        startedAt.set(System.currentTimeMillis());
        var req = exchange.request;
        var backend = backendContext.backend;

        final String requestURI = req.getRequestURI();
        final String queryString = req.getQueryString();
        String targetUri = requestURI;
        if (StringUtils.isNotBlank(queryString)) {
            targetUri = requestURI + "?" + queryString;
        }
        String uri = backend.cutBackendUri(targetUri);

        lifecycle.obtainedBackendInfoOnRequest(backend.name(), requestURI);

        lifecycle.calculatedFullBackendURL(targetUrl);
        final String requestUri = uri;

        ProxyClientCallContext telemetryContext = ProxyClientCallContext.start(tracer);

        // MDC снимается в потоке сервлета: в event loop Netty его нет, он восстанавливается на ключевых событиях.
        Map<String, String> mdc = new HashMap<>(ofNullable(MDC.getCopyOfContextMap())
                .orElseGet(Collections::emptyMap));

        ProxyCommandSupport.DataToSend request;
        try {
            request = telemetryContext.inScopeWithAuth(this.authentication.getName(), () -> {
                try {
                    return ProxyCommandSupport.buildFullHttpRequest(
                            req, requestUri, backend,
                            pipeline.headerProcessor, pipeline.enrichContributors,
                            backendContext.channelManager.isKeepAlive(), lifecycle);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Throwable e) {
            telemetryContext.closeError(e);
            throw e;
        }

        var responseContext = BackendResponseContext.builder()
                .request(req)
                .response(exchange.response)
                .backend(backend)
                .channelManager(backendContext.channelManager)
                .headerProcessor(pipeline.headerProcessor)
                .bodyRewriter(pipeline.bodyRewriter)
                .maxRewriteBytes(backendContext.maxRewriteBytes())
                .ownership(ownership)
                .completion(new ServletExchangeCompletion())
                .lifecycle(lifecycle)
                .telemetry(telemetryContext)
                .mdc(mdc)
                .sink(sink)
                .targetUrl(targetUrl)
                .startedAt(startedAt.get())
                .build();
        BackendResponseHandler handler = new BackendResponseHandler(responseContext);
        // [Ошибка 2] Отмену присылает TimeLimiter по таймауту: handler закроет канал и освободит буферы тела.
        sink.onCancel(handler::cancel);

        // [Шаг 3] Канал приходит в event loop и передаётся в шаг 4.
        // [Ошибка 3] Не удалось подключиться к бэкенду - отвечаем как на таймаут, через fallback-ветку.
        // Тело запроса так и не ушло в канал, поэтому освобождается здесь.
        backendContext.channelManager
                .connect(channel ->
                                telemetryContext.withContext(mdc, () ->
                                        attachHandler(channel, handler, request)),
                        () -> {
                            request.release();
                            Throwable t = new BackendTimeoutException(backend, targetUrl);
                            telemetryContext.withContext(mdc, () -> {
                                telemetryContext.closeError(t);
                                sink.error(t);
                            });

                            return null;
                        });
    }

    /**
     * [Шаг 4] Подключает обработчик к полученному каналу и отправляет запрос; выполняется в event loop канала.
     * <p>
     * Запрос мог быть отменён, пока ожидался канал: тогда канал сразу возвращается в пул.
     * Если отмена пришла во время {@code addLast}, её уже обработал {@code handlerAdded}.
     * В обоих случаях тело запроса в канал не попало, поэтому Netty его не освободит - это делается здесь.
     */
    private void attachHandler(Channel channel, BackendResponseHandler handler, ProxyCommandSupport.DataToSend request) {
        if (handler.isCancelled()) {
            backendContext.channelManager.close(channel);
            request.release();
            return;
        }

        // [Шаг 4.1] Netty синхронно вызывает handlerAdded.
        channel.pipeline()
                .addLast(handler);
        if (handler.isCancelled()) {
            request.release();
            return;
        }
        lifecycle.connectedToBackend();
        // [Шаг 4.2]
        sendRequestToBackend(channel, request, handler);
        // [Шаг 4.3] Чтение ответа запрашивается явно: с autoRead=false канал сам из сокета не читает.
        handler.requestResponse();
    }

    /**
     * [Шаг 4.2] Отправка запроса бэкенду. Ошибка отправки (например, бэкенд закрыл соединение)
     * завершает обмен через обработчик ответа - [Ошибка 1].
     */
    private void sendRequestToBackend(Channel channel, ProxyCommandSupport.DataToSend request, BackendResponseHandler handler) {
        ChannelPromise channelPromise = channel.newPromise();
        channelPromise.addListener(future -> {
            lifecycle.sentRequestToBackend();
            if (!future.isSuccess()) {
                handler.failExchange(future.cause());
            }
        });
        if (request.isChunked()) {
            // Форма (multipart) пересобрана HttpPostRequestEncoder и уходит по частям через ChunkedWriteHandler.
            channel.write(request.getHttpRequest(), channel.voidPromise());
            channel.writeAndFlush(request.getEncoder(), channelPromise);
        } else {
            channel.writeAndFlush(request.getHttpRequest(), channelPromise);
        }
    }

    /**
     * [Ошибка 3] Ошибка и таймаут логируются и учитываются в метриках всегда, а сообщение клиенту пишется,
     * только если ответ ещё не занят обработчиком бэкенда: иначе он уже сам записал ошибку или оборвал ответ.
     */
    private int failRequest(Throwable t) {
        lifecycle.failRequest();
        log.error("Запрос к \"{}\" провалился", this.targetUrl, t);
        if (ownership.claimForFallback()) {
            writeErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, BACKEND_FAILURE_MESSAGE);
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private int timeoutRequest() {
        lifecycle.failRequest();
        if (startedAt.get() > 0) {
            log.error("Запрос к {} превысил время ожидания ({} мс)", this.targetUrl, System.currentTimeMillis() - startedAt.get());
        }
        if (ownership.claimForFallback()) {
            writeErrorResponse(HttpStatus.REQUEST_TIMEOUT, BACKEND_UNAVAILABLE_MESSAGE);
        }
        return HttpStatus.REQUEST_TIMEOUT.value();
    }

    private void writeErrorResponse(HttpStatus status, String message) {
        var resp = exchange.response;
        resp.setStatus(status.value());
        resp.setCharacterEncoding(UTF_8.name());
        try (PrintStream printStream = new PrintStream(resp.getOutputStream(), false, UTF_8)) {
            printStream.print(message);
        } catch (IOException ignored) {
            // клиент уже отключился
        } finally {
            completeAsync();
        }
    }

    private static boolean isTimeoutLike(Throwable e) {
        return e instanceof BackendTimeoutException
                || e instanceof BulkheadFullException
                || e instanceof TimeoutException;
    }

    /**
     * [Шаг 8.3], [Ошибка 1, 3] Завершает асинхронную обработку сервлетного запроса. Может вызываться и обработчиком ответа,
     * и fallback-веткой, поэтому повторный вызов безопасен.
     */
    private void completeAsync() {
        var asyncContext = exchange.asyncContext;
        if (asyncContext == null || !exchange.request.isAsyncStarted()) {
            return;
        }
        try {
            asyncContext.complete();
        } catch (IllegalStateException e) {
            log.debug("Асинхронная обработка запроса к {} уже завершена", targetUrl, e);
        }
    }

    private String resolveTargetUrl() {
        var req = exchange.request;
        var requestURI = req.getRequestURI();
        var queryString = req.getQueryString();
        var backend = backendContext.backend;

        var targetUri = requestURI;
        if (StringUtils.isNotBlank(queryString)) {
            targetUri = requestURI + "?" + queryString;
        }
        var uri = backend.cutBackendUri(targetUri);

        return backend.fullBackendUrl(uri);
    }

    /**
     * Действия над сервлетным обменом, которые {@link BackendResponseHandler} выполняет, когда ответ принадлежит ему.
     */
    private class ServletExchangeCompletion implements BackendResponseContext.ExchangeCompletion {

        // [Шаг 8.3]; при [Ошибка 1] - если тело уже начало уходить клиенту.
        @Override
        public void completeExchange() {
            completeAsync();
        }

        /**
         * [Ошибка 1] Ответ захвачен handler'ом, но клиенту ещё ничего не ушло: пишется сообщение об ошибке.
         * Отмена приходит от TimeLimiter, поэтому отвечает так же, как {@link #timeoutRequest()}.
         */
        @Override
        public void renderBackendFailure(Throwable cause) {
            // Сбрасываются уже применённые заголовки бэкенда (Content-Type, Content-Length и т.п.).
            exchange.response.reset();
            if (cause instanceof CancellationException) {
                writeErrorResponse(HttpStatus.REQUEST_TIMEOUT, BACKEND_UNAVAILABLE_MESSAGE);
            } else {
                writeErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, BACKEND_FAILURE_MESSAGE);
            }
        }
    }

    /**
     * Контейнер HTTP-обмена (request + response + async).
     */
    @Value
    @Builder
    public static class ServletExchange {
        HttpServletRequest request;
        HttpServletResponse response;
        @Nullable AsyncContext asyncContext;
    }

    /**
     * Целевой бэкенд, его Netty-транспорт и ограничение на размер контента.
     */
    @Value
    @Builder
    public static class BackendContext {
        private static final long BYTES_IN_MEGABYTE = 1024L * 1024L;

        Backend backend;
        ChannelManager channelManager;
        int maxContentLengthMb;

        /**
         * Предел тела, накапливаемого для перезаписи; {@link io.netty.buffer.CompositeByteBuf} адресуется int.
         */
        long maxRewriteBytes() {
            return Math.min(BYTES_IN_MEGABYTE * maxContentLengthMb, Integer.MAX_VALUE);
        }
    }

    /**
     * Пайплайн обработки заголовков и контента при проксировании.
     */
    @Value
    @Builder
    public static class ContentPipeline {
        HeaderProcessor headerProcessor;
        ResponseBodyRewriter bodyRewriter;
        List<PortalHeaderContributor> enrichContributors;
    }
}
