package com.otr.portal.proxy;

import com.otr.portal.proxy.rewriters.BodyRewriteRequest;
import com.otr.portal.proxy.streaming.BodyWriteListener;
import com.otr.portal.proxy.streaming.BodyWriterContext;
import com.otr.portal.proxy.streaming.ServletBodyWriter;
import com.otr.portal.proxy.streaming.ServletBodyWriterFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.concurrent.CancellationException;

import static java.util.Optional.ofNullable;

/**
 * Мост ответа бэкенда в сервлетный ответ с обратным давлением.
 * <p>
 * Один экземпляр обслуживает одну попытку проксирования (номера шагов - по карте в {@link ProxyCommand}):
 * <ol>
 *     <li>[Шаг 4.1] {@link #handlerAdded} выключает {@code autoRead}, дальше каждое чтение из сокета запрашивается явно;</li>
 *     <li>[Шаг 5] по заголовкам ответа ({@link #receiveHead}) выбирается путь - стриминг или перезапись тела;</li>
 *     <li>[Шаг 6.1, 7] стриминг: каждый чанк сразу передаётся в {@link ServletBodyWriter}, следующее чтение запрашивается
 *     только после {@link #onWritable()}, то есть когда клиент принял предыдущий чанк;</li>
 *     <li>[Шаг 6.2, 6.3] перезапись: чанки копятся в direct {@link CompositeByteBuf}, после последнего тело переписывается
 *     и уходит клиенту одним буфером с итоговым {@code Content-Length};</li>
 *     <li>[Шаг 8], [Ошибка 1] обмен завершается ровно один раз - {@link #completeExchange()} или
 *     {@link #failExchange(Throwable)}, канал при этом возвращается в пул keep-alive или закрывается.</li>
 * </ol>
 * Так в памяти находится не больше одного чанка (стриминг) или одно тело (перезапись),
 * а медленный клиент замедляет чтение с бэкенда вместо накопления данных в портале.
 * <p>
 * Всё состояние меняется только в event loop канала(один поток навсегдла "прилипает" к каналу). Из других потоков приходят лишь {@link #cancel()}
 * (через {@code volatile}-поля) и сигналы writer'а, которые тот сам переводит в event loop.
 */
@Slf4j
final class BackendResponseHandler extends ChannelInboundHandlerAdapter implements BodyWriteListener {
    private final BackendResponseContext context;
    // Пишутся и читаются из разных потоков: отмена может прийти раньше, чем handler попадёт в pipeline.
    private volatile EventLoop eventLoop;
    private volatile boolean cancelled;

    private ChannelHandlerContext channelContext;
    // При перезаписи заголовки применяются к сервлетному ответу только после неё, когда известен Content-Length.
    private HttpResponse head;
    private MediaType contentType = MediaType.ALL;
    private int code; // статус ответа
    private boolean rewriting;
    // Тело, накапливаемое для перезаписи; живёт в direct-памяти Netty, а не в heap.
    private CompositeByteBuf rewriteBody;
    // Создаётся при первой записи тела, когда уже понятно, асинхронно можно писать в ответ или нет.
    private ServletBodyWriter writer;
    private long bodyBytes;
    // read() запрошен, а сообщение ещё не пришло (см. channelReadComplete).
    private boolean readRequested;
    // Последний чанк получен: после записи текущего буфера тело завершается.
    private boolean lastContentReceived;
    private boolean channelReleased;
    // Обмен завершён успешно или с ошибкой; все последующие события игнорируются.
    private boolean finished;

    BackendResponseHandler(BackendResponseContext context) {
        this.context = context;
    }

    boolean isCancelled() {
        return cancelled;
    }

    /**
     * [Шаг 4.3] Запрашивает начало ответа после отправки запроса; вызывается в event loop канала.
     */
    void requestResponse() {
        if (!finished) {
            requestContent();
        }
    }

    /**
     * [Ошибка 2] Отмена подписки (например, по таймауту {@code TimeLimiter}); вызывается из любого потока.
     * <p>
     * Сама отмена выполняется в event loop канала. Если handler ещё не добавлен в pipeline,
     * её выполнит {@link #handlerAdded}.
     */
    void cancel() {
        cancelled = true;
        EventLoop loop = eventLoop;
        if (loop != null) {
            loop.execute(this::failCancelledExchange);
        }
    }

    /**
     * [Шаг 4.1] {@code autoRead} выключается здесь, а не в bootstrap: до начала обмена {@code SslHandler} нужны чтения
     * для рукопожатия, а в пуле keep-alive канал должен читать, чтобы заметить закрытие соединения бэкендом.
     * <p>
     * {@code eventLoop} публикуется до проверки {@code cancelled}, а {@link #cancel()} делает наоборот,
     * поэтому отмену увидит хотя бы одна сторона; повторная отмена безопасна.
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        channelContext = ctx;
        ctx.channel().config().setAutoRead(false);
        eventLoop = ctx.channel().eventLoop();
        if (cancelled) {
            failCancelledExchange();
        }
    }

    /**
     * [Шаг 5, 6] Точка входа каждого прочитанного сообщения: заголовки ответа (шаг 5) или чанк тела (шаг 6).
     * <p>
     * Буфер {@link HttpContent} передаётся дальше - во writer или в тело для перезаписи — и освобождается там.
     * Всё остальное (заголовки, сообщения после завершения обмена) освобождается здесь.
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        readRequested = false;
        boolean contentTransferred = false;
        try {
            if (finished) {
                return;
            }
            if (msg instanceof HttpResponse) {
                receiveHead((HttpResponse) msg);
            }
            if (msg instanceof HttpContent && !finished) {
                contentTransferred = true;
                receiveContent((HttpContent) msg);
            }
        } catch (Throwable e) {
            failExchange(e);
        } finally {
            if (!contentTransferred) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    /**
     * [Шаг 5, 6] Цикл чтения из сокета мог закончиться без сообщения (например, пришла только часть чанка):
     * по контракту {@code read()} он считается выполненным, поэтому спрос повторяется.
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        if (readRequested && !finished) {
            ctx.read();
        }
        ctx.fireChannelReadComplete();
    }

    // [Ошибка 1]
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        failExchange(new PrematureChannelClosureException("Бэкенд закрыл соединение до окончания ответа " + context.getTargetUrl()));
        ctx.fireChannelInactive();
    }

    // [Ошибка 1]
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        failExchange(cause);
    }

    /**
     * [Шаг 7.4] Клиент принял очередной буфер. Только теперь у бэкенда запрашивается следующий (шаг 6) -
     * это и есть обратное давление. После последнего чанка вместо чтения завершается тело (шаг 8.2).
     */
    @Override
    public void onWritable() {
        if (finished) {
            return;
        }
        if (lastContentReceived) {
            writer.complete();
        } else {
            requestContent();
        }
    }

    // [Шаг 8.2] Writer дописал тело клиенту.
    @Override
    public void onCompleted() {
        if (finished) {
            return;
        }
        finished = true;
        withTelemetry(this::completeExchange);
    }

    /**
     * [Ошибка 4] Клиент перестал принимать ответ (обычно закрыл соединение). Читать бэкенд дальше незачем,
     * а канал посреди ответа нельзя вернуть в пул - он закрывается. Для прокси это не сбой бэкенда,
     * поэтому обмен завершается успешно, как раньше при {@code EofException}.
     */
    @Override
    public void onFailed(Throwable cause) {
        if (finished) {
            return;
        }
        finished = true;
        releaseRewriteBody();
        releaseChannel(false);
        withTelemetry(() -> {
            log.debug("Клиент прервал получение ответа {}: {}", context.getTargetUrl(), cause.toString());
            completeExchange();
        });
    }

    /**
     * [Шаг 5] Разбирает заголовки ответа и выбирает путь тела.
     * <p>
     * Стриминг: ответ сразу захватывается, заголовки применяются к сервлетному ответу
     * и уйдут клиенту вместе с первым чанком.
     * Перезапись: объявленный {@code Content-Length} сразу сверяется с лимитом, а заголовки откладываются
     * до конца тела - итоговая длина станет известна только после перезаписи.
     */
    private void receiveHead(HttpResponse response) {
        head = response;
        code = response.status().code();
        contentType = ofNullable(response.headers().get(HttpHeaders.CONTENT_TYPE))
                .map(ProxyCommandSupport::parseMediaType)
                .orElse(MediaType.ALL);
        rewriting = isRewritable(response);
        withTelemetry(() -> {
            context.getLifecycle().recievedBackendResponse();
            log.debug("Ответ {} от {}: Content-Type {}, Content-Length {}, путь {}",
                    code, context.getTargetUrl(), contentType,
                    response.headers().get(HttpHeaders.CONTENT_LENGTH),
                    rewriting
                            ? context.getBodyRewriter().describeMode(contentType)
                            : "stream"
            );
            if (rewriting) {
                verifyDeclaredLength(response);
                rewriteBody = channelContext.alloc()
                        .compositeDirectBuffer(Integer.MAX_VALUE);
            } else if (claimResponse()) {
                applyHead(response);
            }
        });
        if (!finished) {
            requestContent();
        }
    }

    // [Шаг 6] Чанк тела уходит в путь, выбранный в шаге 5.
    private void receiveContent(HttpContent content) {
        ByteBuf data = content.content();
        boolean last = content instanceof LastHttpContent;
        if (rewriting) {
            collectRewriteContent(data, last);
        } else {
            streamContent(data, last);
        }
    }

    /**
     * [Шаг 6.1] Передаёт чанк клиенту без копирования.
     * <p>
     * Канал отпускается сразу после последнего чанка (шаг 8.1), не дожидаясь, пока клиент его примет:
     * буфер удерживает writer, а соединение с бэкендом уже можно отдать следующему запросу.
     */
    private void streamContent(ByteBuf data, boolean last) {
        bodyBytes += data.readableBytes();
        if (last) {
            lastContentReceived = true;
            releaseChannel(true);
        }
        if (data.isReadable()) {
            // [Шаг 7.1, 7.2]
            bodyWriter().write(data);
            return;
        }
        // Пустой чанк (например, завершающий в chunked-ответе) писать незачем: сразу шаг 8.2 или следующее чтение.
        data.release();
        if (last) {
            bodyWriter().complete();
        } else {
            requestContent(); // читаем еще порцию от бэкенда
        }
    }

    /**
     * [Шаг 6.2] Добавляет чанк в тело для перезаписи без копирования: буфер чанка становится компонентом composite.
     * Превышение лимита завершает обмен ошибкой через {@link #channelRead} → {@link #failExchange} - [Ошибка 1].
     */
    private void collectRewriteContent(ByteBuf data, boolean last) {
        long total = bodyBytes + data.readableBytes();
        if (total > context.getMaxRewriteBytes()) {
            data.release();
            throw new TooLongFrameException("Response entity too large: " + total + " > " + context.getMaxRewriteBytes());
        }
        bodyBytes = total;
        if (data.isReadable()) {
            rewriteBody.addComponent(true, data);
        } else {
            data.release();
        }
        if (last) {
            withTelemetry(this::completeRewrite);
        } else {
            requestContent(); // читаем еще порцию от бэкенда
        }
    }

    /**
     * [Шаг 6.3] Тело получено целиком: переписать ссылки, заменить {@code Transfer-Encoding: chunked}
     * на итоговый {@code Content-Length} и отправить клиенту одним буфером.
     * <p>
     * Канал бэкенду больше не нужен и отпускается до перезаписи (шаг 8.1).
     */
    private void completeRewrite() {
        lastContentReceived = true;
        releaseChannel(true);
        CompositeByteBuf body = rewriteBody;
        rewriteBody = null;
        context.getLifecycle()
                .startedContentRewrite();
        var rewriteRequest = BodyRewriteRequest.builder()
                .body(body)
                .contentType(contentType)
                .backend(context.getBackend())
                .request(context.getRequest())
                .allocator(channelContext.alloc())
                .build();
        ByteBuf rewritten = context.getBodyRewriter()
                .rewrite(rewriteRequest);
        context.getLifecycle().endContentRewrite();
        if (!claimResponse()) { // Мог быть таймаут Команды и ответ отдавать уже не надо
            rewritten.release();
            return;
        }
        HttpUtil.setTransferEncodingChunked(head, false);
        applyHead(head);
        context.getResponse()
                .setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(rewritten.readableBytes()));
        log.debug("Тело {} перезаписано: {} -> {} байт", context.getTargetUrl(), bodyBytes, rewritten.readableBytes());
        if (rewritten.isReadable()) {
            // [Шаг 7.1, 7.2]
            bodyWriter().write(rewritten);
        } else {
            rewritten.release();
            bodyWriter().complete();
        }
    }

    /**
     * Перезаписывается только несжатый текст; двоичные ответы стримятся и на хостах с перезаписью.
     */
    private boolean isRewritable(HttpResponse response) {
        String contentEncoding = response.headers().get(HttpHeaderNames.CONTENT_ENCODING);
        return context.getBackend().isReplaceUrlInContent()
                && (contentEncoding == null || HttpHeaderValues.IDENTITY.contentEqualsIgnoreCase(contentEncoding.trim()))
                && !ProxyCommandSupport.isBinaryResponse(context.getRequest(), contentType);
    }

    private void verifyDeclaredLength(HttpResponse response) {
        long declared = HttpUtil.getContentLength(response, -1L);
        if (declared > context.getMaxRewriteBytes()) {
            throw new TooLongFrameException("Response entity too large: " + declared + " > " + context.getMaxRewriteBytes());
        }
    }

    /**
     * [Шаг 5 - стриминг, 6.3 - перезапись] Захватывает сервлетный ответ перед первым изменением. Если его уже заняла fallback-ветка
     * (таймаут или ошибка), обмен сворачивается без записи в ответ: канал закрывается, буферы освобождаются.
     */
    private boolean claimResponse() {
        if (context.getOwnership().claimForBackend(this)) {
            return true;
        }
        finished = true;
        releaseRewriteBody();
        releaseChannel(false);
        context.getTelemetry()
                .closeError(new CancellationException("Ответ на " + context.getTargetUrl() + " уже сформирован fallback-веткой"));

        return false;
    }

    private void applyHead(HttpResponse response) {
        context.getResponse().setStatus(code);
        context.getHeaderProcessor().consumeHeaders(
                ProxyCommandSupport.extractHeaders(response),
                context.getRequest(),
                context.getResponse(),
                context.getBackend());
        context.getLifecycle().processedBackendHeaders();
    }

    // [Шаг 7.1]
    private ServletBodyWriter bodyWriter() {
        if (writer == null) {
            writer = ServletBodyWriterFactory.createWriter(BodyWriterContext.builder()
                    .request(context.getRequest())
                    .response(context.getResponse())
                    .listener(this)
                    .callbackExecutor(eventLoop)
                    .build());
        }
        return writer;
    }

    /**
     * Одно чтение из сокета; {@code FlowControlHandler} в pipeline выдаёт на него не больше одного {@code HttpObject}.
     * Вызывается в шагах 4.3, 5, 6.1, 6.2 и 7.4; результат чтения приходит в {@link #channelRead} (шаги 5, 6).
     */
    private void requestContent() {
        readRequested = true;
        channelContext.read();
    }

    /**
     * [Шаг 8.3] Успешное завершение: метрики, завершение async-обработки запроса, закрытие спана и результат в {@code Mono}.
     * Этим же методом заканчивается [Ошибка 4].
     */
    private void completeExchange() {
        log.debug("Запрос к {} был проксирован за {} мс. Код ответа {}",
                context.getTargetUrl(), System.currentTimeMillis() - context.getStartedAt(), code);
        context.getLifecycle()
                .processedBackendResponse(bodyBytes, code);
        context.getCompletion()
                .completeExchange();
        context.getTelemetry()
                .close(code);
        context.getSink()
                .success(code);
    }

    // [Ошибка 2] → [Ошибка 1]
    private void failCancelledExchange() {
        failExchange(new CancellationException("Проксирование запроса к " + context.getTargetUrl() + " отменено"));
    }

    /**
     * [Ошибка 1] Ошибка обмена: сбой или обрыв бэкенда, превышение лимита, отмена по таймауту.
     * <p>
     * Если ответ уже захвачен этим handler'ом, он оформляется здесь (см. {@link #terminateOwnedResponse}).
     * Если ещё нет, ответ с ошибкой напишет fallback-ветка {@link ProxyCommand}, получив {@code sink.error} — [Ошибка 3].
     * <p>
     * Вызывается только в event loop канала.
     */
    void failExchange(Throwable cause) {
        if (finished) {
            return;
        }
        finished = true;
        releaseRewriteBody();
        releaseChannel(false);
        withTelemetry(() -> {
            context.getTelemetry().closeError(cause);
            if (context.getOwnership().isOwnedBy(this)) {
                terminateOwnedResponse(cause);
            }
            context.getSink().error(cause);
        });
    }

    /**
     * [Ошибка 1] Пока клиенту ничего не отправлено, ответ заменяется сообщением об ошибке подсистемы.
     * Если тело уже начало уходить, заменить его нельзя: соединение с клиентом обрывается,
     * чтобы клиент не принял обрезанное тело за полное.
     */
    private void terminateOwnedResponse(Throwable cause) {
        boolean started = writer != null
                && writer.isStarted()
                || context.getResponse().isCommitted();
        if (writer != null) {
            writer.abort(cause);
        }
        if (started) {
            context.getCompletion()
                    .completeExchange();
        } else {
            context.getCompletion()
                    .renderBackendFailure(cause);
        }
    }

    private void releaseRewriteBody() {
        if (rewriteBody != null) {
            rewriteBody.release();
            rewriteBody = null;
        }
    }

    /**
     * [Шаг 8.1], [Ошибка 1, 4] Отсоединяет handler от канала. Канал, дочитанный до конца ответа, можно вернуть в пул keep-alive;
     * прерванный - только закрыть: в нём могут остаться непрочитанные байты этого ответа,
     * которые следующий запрос принял бы за свои.
     */
    private void releaseChannel(boolean reusable) {
        if (channelReleased || channelContext == null) {
            return;
        }
        channelReleased = true;
        Channel channel = channelContext.channel();
        if (channelContext.pipeline().context(this) != null) {
            channelContext.pipeline()
                    .remove(this);
        }
        if (reusable) {
            context.getChannelManager()
                    .close(channel);
        } else {
            channel.close();
        }
    }

    /**
     * Колбэки Netty и Jetty выполняются без MDC и контекста трассировки запроса; на время действия они восстанавливаются.
     * Вызывается только на ключевых событиях, не на каждом чанке.
     */
    private void withTelemetry(Runnable action) {
        context.getTelemetry()
                .withContext(context.getMdc(), action);
    }
}
