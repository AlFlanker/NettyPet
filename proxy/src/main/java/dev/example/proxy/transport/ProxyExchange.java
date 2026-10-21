package dev.example.proxy.transport;

import dev.example.proxy.api.Headers;
import dev.example.proxy.api.ProxyMetrics;
import dev.example.proxy.api.ProxyOptions;
import dev.example.proxy.api.ProxyResult;
import dev.example.proxy.core.HeaderRules;
import dev.example.proxy.core.Origin;
import dev.example.proxy.core.UpstreamUri;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Один потоковый HTTP-обмен: основные сетевые операции выполняются в закреплённом
 * цикле событий Netty. Обработчики Servlet сообщают о готовности или запрещают дальнейший I/O.
 * servletGuard объединяет проверку жизненного цикла и обращение к Servlet: завершение
 * контейнера не позволяет следующему проходу обработки использовать переиспользованный запрос.
 * Под монитором нет ожидания Future. После остановки цикла аварийная очистка может
 * выполняться глобальным исполнителем Netty, но уже без обращений к Servlet.
 */
final class ProxyExchange implements ProxyCall {
    private static final Logger log = LoggerFactory.getLogger(ProxyExchange.class);
    private static final int SMALL_FRAGMENT_BYTES = 1024;
    private final NettyTransport transport;
    private final ProxyOptions config;
    private final EventLoop loop;
    private final AsyncContext async;
    private final HttpServletRequest incoming;
    private final HttpServletResponse outgoing;
    private final PreparedRequest plan;
    private final ProxyMetrics metrics;
    private final long startedNanos = System.nanoTime();
    private final Object servletGuard = new Object();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean containerStopped = new AtomicBoolean();
    private final AtomicBoolean admissionReleased = new AtomicBoolean();
    private final AtomicBoolean cancellationQueued = new AtomicBoolean();
    private final CompletableFuture<ProxyResult> completion = new CompletableFuture<>();
    private final CompletionStage<ProxyResult> completionView = completion.minimalCompletionStage();
    private final ArrayDeque<ByteBuf> responseQueue = new ArrayDeque<>();
    // Один Runnable переиспользуется всеми сигналами обмена, чтобы не выделять новую лямбду на каждом событии.
    private final Runnable pump = this::runPump;
    private final DownstreamWriter.Completion writeCompletion = this::downstreamWriteCompleted;
    private final Runnable completedWrite = () -> finishDownstreamWrite(true);
    private final Runnable rejectedCompletedWrite = () -> finishDownstreamWrite(false);
    private volatile Throwable writeFailure;
    private FixedChannelPool pool;
    private Future<Channel> acquisition;
    private ScheduledFuture<?> deadline;
    private ScheduledFuture<?> responseHeadersDeadline;
    private Channel channel;
    private ServletInputStream input;
    private ServletOutputStream output;
    private DownstreamWriter downstreamWriter;
    private ByteBuf writingBuffer;
    private byte[] outputScratch;
    private boolean directInput, writerBusy;
    private boolean terminal, initialized, headersSent, uploadBusy, uploadEnded, uploadStopped;
    private boolean finalResponse, interimResponse, responseEnded, readPending, keepAlive, noBody;
    private boolean flushPending;
    private boolean acquisitionObserved = true, cleanupPrepared;
    private int pendingCleanup;
    private ProxyResult.Outcome outcome;
    private int queuedBytes, peakQueuedBytes, responseStatus;
    private long uploaded, downloaded;
    private final long contentLength;

    ProxyExchange(NettyTransport transport, ProxyOptions config, AsyncContext async,
                  HttpServletRequest incoming, HttpServletResponse outgoing,
                  PreparedRequest plan, ProxyMetrics metrics) {
        this.transport = transport;
        this.config = config;
        this.loop = transport.nextLoop();
        this.async = async;
        this.incoming = incoming;
        this.outgoing = outgoing;
        this.plan = plan;
        this.metrics = metrics;
        contentLength = plan.contentLength();
    }

    /** Возвращает сигнал, который завершается после обработки всех полученных каналов и окончания очистки. */
    @Override public CompletionStage<ProxyResult> completion() { return completionView; }

    /** Ставит единственный запрос отмены в цикл событий; вызывающий поток не обращается к Servlet. */
    @Override public void cancel(int status) {
        if (status < 400 || status > 599) throw new IllegalArgumentException("Cancellation status must be 400..599");
        if (completion.isDone() || !cancellationQueued.compareAndSet(false, true)) return;
        try {
            loop.execute(() -> fail(status, new IOException("Proxy call cancelled"), ProxyResult.Outcome.CANCELLED));
        } catch (RejectedExecutionException ignored) {
            // Transport.close уже поставил очистку до остановки циклов событий.
            // Здесь нельзя обращаться к Servlet или преждевременно подтверждать освобождение ресурсов.
        }
    }

    /** Подключает жизненный цикл Servlet и ставит получение канала в очередь закреплённого цикла событий. */
    void start() {
        synchronized (servletGuard) {
            try {
                // Срок обмена контролирует закреплённый цикл событий. Таймаут Servlet выключен,
                // чтобы контейнер не завершил ответ раньше обработки сетевого таймаута.
                async.setTimeout(0);
                async.addListener(new AsyncListener() {
                    @Override public void onComplete(AsyncEvent event) { containerFinished(); }
                    @Override public void onError(AsyncEvent event) { containerFinished(); }
                    @Override public void onTimeout(AsyncEvent event) { containerFinished(); }
                    @Override public void onStartAsync(AsyncEvent event) { }
                });
                loop.execute(this::acquire);
            } catch (RuntimeException failure) {
                // Даже синхронный сбой после startAsync обрабатывает этот обмен:
                // фасад не должен повторно обращаться к уже завершённому Servlet-запросу.
                try { fail(500, failure, ProxyResult.Outcome.LOCAL_REJECTION); }
                catch (Throwable cleanupFailure) {
                    if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            } catch (Error failure) {
                outcome = ProxyResult.Outcome.LOCAL_REJECTION;
                responseStatus = 500;
                try {
                    if (!containerStopped.getAndSet(true)) transport.abortDownstream(incoming, failure);
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                } finally {
                    try { terminate(false, false); }
                    catch (Throwable cleanupFailure) {
                        if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }
    }

    /** Запрещает дальнейшие обращения к Servlet и передаёт оставшуюся очистку циклу событий. */
    private void containerFinished() {
        synchronized (servletGuard) { containerStopped.set(true); }
        try { loop.execute(() -> terminate(false, false)); }
        catch (RejectedExecutionException ignored) {
            // close() ставит очистку до остановки циклов событий. Позднее уведомление
            // не должно выбрасывать исключение в контейнер; результат завершает только очистка обмена.
        }
    }

    /** Прерывает ответ при ошибке Servlet I/O и запускает очистку, не полагаясь на таймаут контейнера. */
    private void servletIoFailed(Throwable cause) {
        synchronized (servletGuard) {
            // Read/WriteListener.onError сообщает об ошибке I/O, но не завершает AsyncContext.
            // При отключённом таймауте Servlet одной очистки недостаточно: ответ нужно прервать.
            if (!containerStopped.getAndSet(true)) {
                try { transport.abortDownstream(incoming, cause); }
                catch (RuntimeException abortFailure) { log.debug("Container I/O abort failed", abortFailure); }
                finally { containerFinished(); }
            }
        }
    }

    /** Регистрирует обмен, запускает оставшийся общий таймер и запрашивает канал из ограниченного пула. */
    private void acquire() {
        if (terminal || containerStopped.get()) { terminate(false, false); return; }
        if (!transport.register(this)) {
            fail(503, new IOException("Proxy shutting down"), ProxyResult.Outcome.LOCAL_REJECTION); return;
        }
        try {
            // Ожидание в очереди цикла событий входит в общий срок обмена. После задержки
            // старта нельзя заново выделять запросу весь настроенный бюджет времени.
            long remaining = config.exchangeTimeout().toNanos() - (System.nanoTime() - startedNanos);
            if (remaining <= 0) {
                fail(504, new IOException("Exchange deadline exceeded before acquire"), ProxyResult.Outcome.DEADLINE_EXCEEDED);
                return;
            }
            deadline = loop.schedule(() -> fail(504, new IOException("Exchange deadline exceeded"),
                            ProxyResult.Outcome.DEADLINE_EXCEEDED),
                    remaining, TimeUnit.NANOSECONDS);
            pool = transport.pool(Origin.from(plan.uri()), loop);
            acquisitionObserved = false;
            try { acquisition = pool.acquire(); }
            catch (Throwable failure) { acquisitionObserved = true; throw failure; }
            acquisition.addListener(ignored -> acquired(acquisition));
        } catch (Throwable failure) { acquireFailed(failure); }
    }

    /** Обрабатывает результат выдачи канала; при позднем успехе отменённого обмена возвращает канал без отправки нового запроса. */
    private void acquired(Future<Channel> result) {
        synchronized (servletGuard) {
            // isDone() недостаточно: Netty может отложить вызов обработчиков.
            // completion ждёт обработки результата получения канала и его возврата в пул.
            acquisitionObserved = true;
            if (!result.isSuccess()) {
                if (!terminal) acquireFailed(result.cause());
                finishCleanupIfReady();
                return;
            }
            Channel acquired = result.getNow();
            if (terminal || containerStopped.get()) {
                cleanupChannel(acquired, true);
                terminate(false, false);
                finishCleanupIfReady();
                return;
            }
            channel = acquired;
            channel.attr(NettyTransport.EXCHANGE).set(this);
            try { initialize(); }
            catch (IOException failure) { servletIoFailed(failure); }
            catch (Throwable failure) { fail(ProxyFailure.upstreamStatus(failure), failure); }
        }
    }

    /** Преобразует отказ пула либо подключения в локальный отказ или ошибку целевого сервера. */
    private void acquireFailed(Throwable failure) {
        int status = ProxyFailure.acquireStatus(failure);
        fail(status, failure, status == 503 ? ProxyResult.Outcome.LOCAL_REJECTION : ProxyResult.Outcome.UPSTREAM_FAILURE);
    }

    /** Подключает асинхронные обработчики Servlet и отправляет подготовленные заголовки запроса серверу. */
    private void initialize() throws IOException {
        synchronized (servletGuard) {
            if (containerStopped.get()) { terminate(false, false); return; }
            input = incoming.getInputStream();
            directInput = transport.supportsDirectInput(input);
            downstreamWriter = transport.createWriter(incoming, outgoing);
            if (downstreamWriter == null) {
                output = outgoing.getOutputStream();
                output.setWriteListener(new WriteListener() {
                    @Override public void onWritePossible() { signal(); }
                    @Override public void onError(Throwable cause) { servletIoFailed(cause); }
                });
            }
            if (containerStopped.get()) { terminate(false, false); return; }
            input.setReadListener(new ReadListener() {
                @Override public void onDataAvailable() { signal(); }
                @Override public void onAllDataRead() { signal(); }
                @Override public void onError(Throwable cause) { servletIoFailed(cause); }
            });
            if (containerStopped.get()) { terminate(false, false); return; }
            var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(plan.method()),
                    UpstreamUri.requestTarget(plan.uri()));
            plan.headers().forEach((name, values) -> request.headers().add(name, values));
            request.headers().set(HttpHeaderNames.HOST, Origin.from(plan.uri()).authority());
            if (plan.declaredLength()) HttpUtil.setContentLength(request, contentLength);
            else if (contentLength < 0) HttpUtil.setTransferEncodingChunked(request, true);
            initialized = true;
            channel.writeAndFlush(request).addListener(result -> {
                if (!result.isSuccess()) { fail(ProxyFailure.upstreamStatus(result.cause()), result.cause()); return; }
                headersSent = true;
                signal();
            });
            signal(); // Ответ читается параллельно отправке тела: сервер может досрочно вернуть 413 или 401.
        }
    }

    /** Объединяет сигналы готовности в проход обработки, поставленный в очередь цикла событий. */
    void signal() {
        // Всегда ставим обработку в очередь: синхронное завершение записи не должно
        // рекурсивно запускать отправку следующей порции тела. Для обмена ожидает
        // выполнения не более одного прохода обработки.
        if (containerStopped.get() || admissionReleased.get() || !scheduled.compareAndSet(false, true)) return;
        try {
            loop.execute(pump);
        } catch (RejectedExecutionException ignored) { scheduled.set(false); }
    }

    /** Продвигает готовые операции чтения и записи, затем при необходимости запрашивает новые данные сервера. */
    private void runPump() {
        scheduled.set(false);
        synchronized (servletGuard) {
            if (terminal || containerStopped.get() || !initialized) return;
            try { pumpDownload(); pumpUpload(); demand(); }
            catch (IOException failure) { servletIoFailed(failure); }
            catch (Throwable failure) { fail(ProxyFailure.upstreamStatus(failure), failure); }
        }
    }

    /** Передаёт не более одной готовой порции тела запроса, соблюдая готовность Servlet и канала Netty. */
    private void pumpUpload() throws IOException {
        if (terminal || containerStopped.get() || !headersSent || uploadBusy || uploadEnded
                || uploadStopped || !channel.isWritable()) return;
        boolean finished = input.isFinished();
        if (containerStopped.get()) return;
        if (finished) { endUpload(); return; }
        if (!input.isReady() || containerStopped.get()) return;
        ByteBuf bytes = directInput
                ? channel.alloc().directBuffer(config.chunkBytes(), config.chunkBytes())
                : channel.alloc().heapBuffer(config.chunkBytes(), config.chunkBytes());
        int count;
        try {
            // Jetty читает в прямой ByteBuffer без промежуточного массива стандартного Servlet API.
            // Поэтому транспорт NIO не копирует данные из кучи JVM в прямой буфер.
            count = directInput
                    ? input.read(bytes.internalNioBuffer(0, bytes.capacity()))
                    : input.read(bytes.array(), bytes.arrayOffset(), bytes.capacity());
        }
        catch (Throwable failure) { bytes.release(); throw failure; }
        if (containerStopped.get()) { bytes.release(); return; }
        if (count <= 0) {
            bytes.release();
            if (count == -1) endUpload();
            return;
        }
        uploaded += count;
        if (contentLength >= 0 && uploaded > contentLength) {
            bytes.release();
            fail(400, new IOException("Request Content-Length exceeded"), ProxyResult.Outcome.LOCAL_REJECTION);
            return;
        }
        bytes.writerIndex(count);
        uploadBusy = true;
        // Одновременно отправляется не более одного буфера тела; после write он принадлежит Netty.
        // После завершения записи явно возобновляем обработку: ReadListener может больше не вызваться.
        channel.writeAndFlush(new DefaultHttpContent(bytes)).addListener(result -> {
            uploadBusy = false;
            if (!result.isSuccess()) {
                if (!finalResponse) fail(ProxyFailure.upstreamStatus(result.cause()), result.cause());
                return;
            }
            signal();
        });
    }

    /** Проверяет объявленную длину и отправляет конец тела; таймер заголовков запускается после успешной записи. */
    private void endUpload() {
        if (contentLength >= 0 && uploaded != contentLength) {
            fail(400, new IOException("Incomplete request body"), ProxyResult.Outcome.LOCAL_REJECTION);
            return;
        }
        uploadBusy = true;
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(result -> {
            uploadBusy = false;
            if (!result.isSuccess()) {
                if (!finalResponse) fail(ProxyFailure.upstreamStatus(result.cause()), result.cause());
                return;
            }
            uploadEnded = true;
            awaitResponseHeaders();
            signal();
        });
    }

    /** Ограничивает ожидание финальных заголовков после полной отправки запроса целевому серверу. */
    private void awaitResponseHeaders() {
        synchronized (servletGuard) {
            if (terminal || containerStopped.get() || finalResponse) return;
            // Таймер измеряет только ожидание ответа сервера после полной отправки тела запроса.
            // Медленная загрузка тела клиентом и его обратное давление не считаются ошибкой сервера.
            responseHeadersDeadline = loop.schedule(() -> {
                synchronized (servletGuard) {
                    if (!terminal && !containerStopped.get() && !finalResponse)
                        fail(504, new IOException("Upstream response headers timeout"),
                                ProxyResult.Outcome.UPSTREAM_FAILURE);
                }
            }, config.responseHeadersTimeout().toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    /** Проверяет HTTP-ответ и удерживает непустые части тела в ограниченной очереди до записи клиенту. */
    void received(Object message) throws IOException {
        synchronized (servletGuard) {
            if (terminal || containerStopped.get()) return;
            if (message instanceof HttpObject object && !object.decoderResult().isSuccess())
                throw new IOException("Invalid upstream HTTP", object.decoderResult().cause());
            if (responseEnded) throw new IOException("Unexpected data after response end");
            if (message instanceof HttpResponse response) {
                int status = response.status().code();
                // Netty допускает и трёхзначные коды вне HTTP-диапазона. Не передаём их
                // клиенту и не принимаем 000..099 за бесконечный промежуточный ответ.
                if (status < 100 || status > 599) throw new IOException("Invalid upstream HTTP status");
                if (status == 101) throw new IOException("Protocol upgrade is unsupported");
                if (status < 200) { interimResponse = true; return; }
                if (finalResponse) throw new IOException("Multiple final responses");
                // HttpClientCodec разбирает границы chunked, но не распаковывает транспортное gzip.
                // Нельзя удалить Transfer-Encoding и передать клиенту всё ещё закодированные байты.
                if (!TransferCodings.supported(response.headers().getAll(HttpHeaderNames.TRANSFER_ENCODING)))
                    throw new IOException("Unsupported upstream transfer coding");
                if (status == 205 && HttpUtil.getContentLength(response, 0) != 0)
                    throw new IOException("205 response cannot contain content");
                interimResponse = false;
                finalResponse = true;
                if (responseHeadersDeadline != null) responseHeadersDeadline.cancel(false);
                responseStatus = status;
                flushPending = true;
                keepAlive = HttpUtil.isKeepAlive(response);
                noBody = plan.method().equals("HEAD") || status == 204 || status == 205 || status == 304;
                if (!uploadEnded) {
                    uploadStopped = true;
                    keepAlive = false;
                    // HTTP/1-соединение клиента нельзя использовать повторно с непрочитанным телом запроса.
                    outgoing.setHeader("Connection", "close");
                }
                outgoing.setStatus(status);
                Headers headers = HeaderRules.response(headerMap(response.headers()));
                if (status == 204) headers.remove("Content-Length");
                headers.forEachValue(outgoing::addHeader);
            }
            if (message instanceof HttpContent content) {
                if (interimResponse) { if (message instanceof LastHttpContent) interimResponse = false; return; }
                if (!finalResponse) throw new IOException("Body before response headers");
                int size = content.content().readableBytes();
                if (size > 0 && noBody && responseStatus == 205)
                    throw new IOException("205 response cannot contain content");
                if (size > 0 && !noBody) {
                    // Одно чтение сокета может дать много мелких частей HTTP-тела. Лимит байтов
                    // ограничивает также число элементов очереди: пустые буферы не добавляются.
                    if (size > config.responseBufferBytes() - queuedBytes)
                        throw new IOException("Upstream read batch exceeds configured buffer budget");
                    responseQueue.add(content.content().retain());
                    queuedBytes += size;
                    peakQueuedBytes = Math.max(peakQueuedBytes, queuedBytes);
                    flushPending = true;
                }
                if (message instanceof LastHttpContent) responseEnded = true;
            }
            signal();
        }
    }

    /** Собирает значения заголовков без учёта регистра имён для применения общих правил фильтрации ответа. */
    private static Map<String, List<String>> headerMap(HttpHeaders headers) {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach(entry -> map.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue()));
        return map;
    }

    /** Передаёт очередь ответа через стандартный Servlet I/O либо выбранный приложением адаптер записи. */
    private void pumpDownload() throws IOException {
        if (!finalResponse || terminal || containerStopped.get()) return;
        if (downstreamWriter != null) {
            pumpNativeDownload();
            return;
        }
        // Servlet 6.1 может удерживать переданный byte[]. Пока isReady() снова не вернёт true,
        // рабочий массив нельзя менять, даже если исходный ByteBuf Netty уже освобождён.
        if (!outputReady()) return;
        while (!responseQueue.isEmpty()) {
            // Для HEAD, 204 и пустого ответа рабочий массив не выделяется. Массив не берётся
            // из пула: стандартный Servlet API может удерживать его даже после попытки прервать ответ.
            if (outputScratch == null) outputScratch = new byte[config.chunkBytes()];
            ByteBuf head = responseQueue.peek();
            int count = Math.min(head.readableBytes(), outputScratch.length);
            head.readBytes(outputScratch, 0, count);
            queuedBytes -= count;
            if (!head.isReadable()) { responseQueue.remove().release(); }
            output.write(outputScratch, 0, count);
            downloaded += count;
            if (!outputReady()) return;
        }
        // Отправляем накопленную порцию без ожидания конца ответа, чтобы не задерживать короткие события и SSE.
        if (flushPending) { flushPending = false; output.flush(); }
        if (!outputReady()) return;
        if (responseEnded && !readPending) terminate(true, keepAlive && uploadEnded && !uploadStopped);
    }

    /** Передаёт представления ByteBuf адаптеру; удерживает память до подтверждения каждой записи или отправки накопленного. */
    private void pumpNativeDownload() throws IOException {
        if (writerBusy) return;
        while (!responseQueue.isEmpty() && !terminal && !containerStopped.get()) {
            ByteBuf head = responseQueue.peek();
            int count;
            java.nio.ByteBuffer view;
            int batchBytes = coalescedPrefixBytes();
            if (batchBytes > 0) {
                // Мелкие части одного чтения сокета иначе потребуют отдельной записи на каждую часть.
                // Объединяем минимум два полных фрагмента, чтобы уменьшить число записей.
                // Крупный или не поместившийся следующий фрагмент остаётся без копирования.
                ByteBuf batch = channel.alloc().directBuffer(batchBytes, batchBytes);
                try {
                    while (batch.isWritable()) {
                        head = responseQueue.remove();
                        int copied = head.readableBytes();
                        try { batch.writeBytes(head); }
                        finally { head.release(); }
                        queuedBytes -= copied;
                    }
                    count = batch.readableBytes();
                    view = batch.nioBuffer();
                } catch (Throwable failure) {
                    batch.release();
                    throw failure;
                }
                writingBuffer = batch;
            } else {
                count = Math.min(head.readableBytes(), config.chunkBytes());
                // Непрерывный участок буфера не копируем. Очередь и незавершённая запись
                // удерживают отдельные ссылки: отмена освобождает очередь, но память
                // переданного представления остаётся занятой до уведомления контейнера.
                view = head.nioBuffer(head.readerIndex(), count);
                writingBuffer = head.retain();
                head.skipBytes(count);
                queuedBytes -= count;
                if (!head.isReadable()) responseQueue.remove().release();
            }
            writerBusy = true;
            pendingCleanup++;
            downloaded += count;
            // Сама запись публикует также ожидающие заголовки. Если тела ещё нет,
            // flushPending сохраняется: заголовки нужно отправить до следующего read().
            if (downstreamWriter.flushesOnWrite()) flushPending = false;
            try { downstreamWriter.write(view, writeCompletion); }
            catch (IOException | RuntimeException | Error failure) {
                downstreamWriteCompleted(failure);
                throw failure;
            }
            if (writerBusy) return;
        }
        if (terminal || containerStopped.get()) return;
        if (flushPending) {
            flushPending = false;
            writerBusy = true;
            pendingCleanup++;
            try { downstreamWriter.flush(writeCompletion); }
            catch (IOException | RuntimeException | Error failure) {
                downstreamWriteCompleted(failure);
                throw failure;
            }
            if (writerBusy) return;
        }
        if (!terminal && !containerStopped.get() && responseEnded && !readPending)
            terminate(true, keepAlive && uploadEnded && !uploadStopped);
    }

    /** Выбирает размер уже доступной группы мелких фрагментов; новые данные ради объединения не ожидаются. */
    private int coalescedPrefixBytes() {
        if (responseQueue.size() < 2 || responseQueue.peek().readableBytes() >= SMALL_FRAGMENT_BYTES) return 0;
        int bytes = 0, fragments = 0;
        // Не ждём новых байтов и не читаем сервер ради объединения. Порог 1 КиБ
        // оставляет обычные части декодера без копии; объединяется только целиком
        // помещающийся в лимит начальный участок очереди.
        for (ByteBuf fragment : responseQueue) {
            int size = fragment.readableBytes();
            if (size >= SMALL_FRAGMENT_BYTES || size > config.chunkBytes() - bytes) break;
            bytes += size;
            fragments++;
        }
        return fragments > 1 ? bytes : 0;
    }

    /** Переносит завершение записи из потока контейнера без захвата блокировки жизненного цикла Servlet. */
    private void downstreamWriteCompleted(Throwable failure) {
        // Jetty может вызвать обработчик завершения под внутренней блокировкой.
        // Здесь нельзя захватывать servletGuard: обработка обмена уже держит его
        // при вызове Jetty, и обратный порядок блокировок мог бы вызвать взаимную блокировку.
        // Единственная незавершённая запись позволяет переиспользовать обработчик и Runnable.
        writeFailure = failure;
        try { loop.execute(completedWrite); }
        catch (RejectedExecutionException ignored) {
            // Цикл событий может остановиться до позднего уведомления после прерывания ответа.
            // Глобальный исполнитель только освобождает ресурсы; обращаться к Servlet здесь нельзя.
            GlobalEventExecutor.INSTANCE.execute(rejectedCompletedWrite);
        }
    }

    /** После уведомления контейнера освобождает буфер записи и продвигает обмен либо завершает аварийную очистку. */
    private void finishDownstreamWrite(boolean ownerAvailable) {
        synchronized (servletGuard) {
            if (!writerBusy) return;
            Throwable failure = writeFailure;
            writeFailure = null;
            writerBusy = false;
            if (writingBuffer != null) {
                writingBuffer.release();
                writingBuffer = null;
            }
            pendingCleanup--;
            // Даже после прерывания ответа буфер возвращается в пул только здесь.
            // Позднее уведомление завершает очистку и не использует Servlet-запрос,
            // который контейнер мог уже переиспользовать.
            if (!ownerAvailable) {
                // NettyTransport.close уже поставил остановку обмена до остановки циклов событий.
                // Даже если та обработка прервалась, terminate(false, false) не обращается к Servlet.
                terminate(false, false);
            } else if (!terminal && !containerStopped.get() && failure != null) servletIoFailed(failure);
            finishCleanupIfReady();
            if (ownerAvailable && !terminal && !containerStopped.get()) signal();
        }
    }

    /** Запрашивает чтение без опережения очереди; после заголовков ответа также учитывает готовность клиента. */
    private void demand() {
        if (terminal || containerStopped.get() || responseEnded || readPending || !responseQueue.isEmpty()) return;
        if (finalResponse && !outputReady()) return;
        readPending = true;
        channel.read();
    }

    /** Проверяет готовность клиента и повторно проверяет остановку контейнера после возможного повторного входа. */
    private boolean outputReady() {
        // isReady() у обёртки Servlet может синхронно вызвать onError повторным входом.
        // Блокировка защищает от другого потока, но после такого вызова нужна новая
        // проверка признака остановки контейнера.
        return !containerStopped.get() && (downstreamWriter != null ? !writerBusy : output.isReady())
                && !containerStopped.get();
    }

    /** Завершает одну группу чтения Netty; следующий read() разрешается через общий проход обработки. */
    void readComplete() { readPending = false; signal(); }
    /** Отличает нормальное закрытие после полного ответа от обрыва, который требует ошибки 502. */
    void upstreamClosed() {
        keepAlive = false;
        readPending = false;
        if (responseEnded) signal();
        else fail(502, new IOException("Upstream closed before end of response"));
    }

    /** Завершает обмен как ошибку целевого сервера с указанным диагностическим статусом. */
    void fail(int status, Throwable cause) {
        fail(status, cause, ProxyResult.Outcome.UPSTREAM_FAILURE);
    }

    /** До отправки заголовков формирует пустой отказ; после их отправки прерывает неполный ответ без успешного окончания. */
    private void fail(int status, Throwable cause, ProxyResult.Outcome failureOutcome) {
        synchronized (servletGuard) {
            if (terminal) return;
            log.debug("Proxy exchange failed, status={}", status, cause);
            if (containerStopped.get()) { terminate(false, false); return; }
            outcome = failureOutcome;
            responseStatus = status;
            boolean complete = false;
            try {
                if (outgoing.isCommitted()) {
                    // complete() после усечённого chunked-ответа отправил бы признак успешного окончания.
                    // Прерываем ответ без ожидания; блокировка защищает request от переиспользования.
                    containerStopped.set(true);
                    transport.abortDownstream(incoming,
                            cause == null ? new IOException("Proxy exchange aborted") : cause);
                } else {
                    outgoing.reset();
                    outgoing.setStatus(status);
                    outgoing.setContentLength(0);
                    outgoing.setHeader("Connection", "close");
                    complete = true;
                }
            } finally {
                // Даже ошибка контейнера или адаптера не должна оставить удержанные буферы и слот допуска.
                terminate(complete, false);
            }
        }
    }

    /** Останавливает обмен и запускает очистку; результат ещё ждёт получения канала и всех незавершённых операций. */
    private void terminate(boolean completeServlet, boolean reusable) {
        synchronized (servletGuard) {
            if (terminal) return;
            terminal = true;
            containerStopped.set(true);
            if (outcome == null) outcome = completeServlet ? ProxyResult.Outcome.RESPONSE : ProxyResult.Outcome.CANCELLED;
            transport.unregister(this);
            if (deadline != null) deadline.cancel(false);
            if (responseHeadersDeadline != null) responseHeadersDeadline.cancel(false);
            while (!responseQueue.isEmpty()) responseQueue.remove().release();
            queuedBytes = 0;
            try {
                if (channel != null) cleanupChannel(channel, reusable);
            } finally {
                try {
                    if (completeServlet) {
                        try { async.complete(); }
                        catch (IllegalStateException ignored) { /* Завершение контейнера выиграло гонку. */ }
                    }
                } finally {
                    cleanupPrepared = true;
                    finishCleanupIfReady();
                }
            }
        }
    }

    /** Снимает привязку канала к обмену и учитывает всю цепочку его закрытия и возврата в пул. */
    private void cleanupChannel(Channel owned, boolean reusable) {
        // Дополнительный счётчик не даёт синхронным обработчикам Netty завершить очистку
        // до регистрации всей цепочки закрытия и возврата канала в пул.
        pendingCleanup++;
        try {
            owned.attr(NettyTransport.EXCHANGE).set(null);
            if (reusable) releaseChannel(owned);
            else closeChannel(owned, ignored -> releaseChannel(owned));
        } finally {
            pendingCleanup--;
            finishCleanupIfReady();
        }
    }

    /** Возвращает канал в пул; неудачный возврат дополнительно требует подтверждённого закрытия. */
    private void releaseChannel(Channel owned) {
        observeCleanup(pool.release(owned), result -> {
            if (!result.isSuccess()) closeChannel(owned, null);
        });
    }

    /** Запускает закрытие и учитывает closeFuture перед продолжением очистки. */
    private void closeChannel(Channel owned, java.util.function.Consumer<Future<?>> after) {
        // Завершение TLS может ожидать отправки close_notify при активном TCP-канале.
        // Канал можно вернуть в работающий пул только после closeFuture.
        // Результат отдельного вызова close() сам по себе не подтверждает закрытие TCP.
        observeCleanup(owned.closeFuture(), after);
        owned.close();
    }

    /** Учитывает сетевую операцию очистки и выполняет продолжение как для готового, так и для отложенного результата. */
    private void observeCleanup(Future<?> future, java.util.function.Consumer<Future<?>> after) {
        pendingCleanup++;
        if (future.isDone()) cleanupFinished(future, after);
        else future.addListener(result -> cleanupFinished(result, after));
    }

    /** Завершает учёт одной операции; её продолжение может зарегистрировать следующие операции очистки. */
    private void cleanupFinished(Future<?> result, java.util.function.Consumer<Future<?>> after) {
        synchronized (servletGuard) {
            try { if (after != null) after.accept(result); }
            finally {
                pendingCleanup--;
                finishCleanupIfReady();
            }
        }
    }

    /** Возвращает слот, записывает метрики и публикует результат только после всех подтверждений освобождения ресурсов. */
    private void finishCleanupIfReady() {
        if (!cleanupPrepared || !acquisitionObserved || pendingCleanup != 0 || completion.isDone()) return;
        // Ожидаемое получение канала не отменяется: после таймаута оно ещё может вернуть
        // живой канал. Результат обмена и слот допуска удерживаются до обработки
        // этого результата, возврата канала и необходимого закрытия.
        releaseAdmissionOnce();
        metrics.finished(responseStatus, outcome == ProxyResult.Outcome.CANCELLED, uploaded, downloaded,
                System.nanoTime() - startedNanos, peakQueuedBytes);
        completion.complete(new ProxyResult(responseStatus, outcome));
    }

    /** Возвращает слот допуска не более одного раза при пересекающихся путях завершения. */
    private void releaseAdmissionOnce() {
        if (admissionReleased.compareAndSet(false, true)) transport.releaseAdmission();
    }

    /** Запрашивает локальный отказ 503 в цикле событий, не ожидая завершения сетевой очистки. */
    void shutdown() {
        try {
            loop.execute(() -> fail(503, new IOException("Proxy shutting down"), ProxyResult.Outcome.LOCAL_REJECTION));
        }
        catch (RejectedExecutionException ignored) {
            // За очистку отвечает уже поставленная остановка. Отклонение нового задания
            // не подтверждает закрытие I/O и не разрешает вернуть слот допуска.
        }
    }
}
