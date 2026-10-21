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
 * Один HTTP-обмен. Очередь, канал и состояние протокола принадлежат одному Netty loop.
 * Servlet callbacks лишь сигнализируют о готовности или синхронно запрещают дальнейший I/O.
 * servletGuard закрывает гонку check-then-use с завершением/recycle Servlet request:
 * после возврата callback завершения ни один pump не обращается к старым Servlet-объектам.
 * Под монитором выполняются только неблокирующие операции, без ожидания Future.
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
    // Один Runnable на exchange: hot path не создаёт capturing lambda на каждый signal.
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

    @Override public CompletionStage<ProxyResult> completion() { return completionView; }

    @Override public void cancel(int status) {
        if (status < 400 || status > 599) throw new IllegalArgumentException("Cancellation status must be 400..599");
        if (completion.isDone() || !cancellationQueued.compareAndSet(false, true)) return;
        try {
            loop.execute(() -> fail(status, new IOException("Proxy call cancelled"), ProxyResult.Outcome.CANCELLED));
        } catch (RejectedExecutionException ignored) {
            // Transport.close уже поставил cleanup до остановки loops. Не трогаем Servlet
            // с вызывающего потока и не выдаём преждевременное подтверждение освобождения.
        }
    }

    void start() {
        synchronized (servletGuard) {
            try {
                // Deadline принадлежит owner loop. Servlet timeout выключен, иначе контейнер
                // может завершить response раньше поставленной в очередь обработки таймаута.
                async.setTimeout(0);
                async.addListener(new AsyncListener() {
                    @Override public void onComplete(AsyncEvent event) { containerFinished(); }
                    @Override public void onError(AsyncEvent event) { containerFinished(); }
                    @Override public void onTimeout(AsyncEvent event) { containerFinished(); }
                    @Override public void onStartAsync(AsyncEvent event) { }
                });
                loop.execute(this::acquire);
            } catch (RuntimeException failure) {
                // Даже синхронный сбой после startAsync принадлежит этому exchange:
                // фасад больше не должен обращаться к уже завершённому Servlet request.
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

    private void containerFinished() {
        synchronized (servletGuard) { containerStopped.set(true); }
        try { loop.execute(() -> terminate(false, false)); }
        catch (RejectedExecutionException ignored) {
            // close() ставит cleanup до остановки loops. Поздний callback не должен
            // выбрасывать исключение в контейнер. Только owner cleanup завершает handle.
        }
    }

    private void servletIoFailed(Throwable cause) {
        synchronized (servletGuard) {
            // Read/WriteListener.onError сообщает об ошибке I/O, а не о завершении
            // AsyncContext. При timeout=0 одного cleanup недостаточно: требуется abort.
            if (!containerStopped.getAndSet(true)) {
                try { transport.abortDownstream(incoming, cause); }
                catch (RuntimeException abortFailure) { log.debug("Container I/O abort failed", abortFailure); }
                finally { containerFinished(); }
            }
        }
    }

    private void acquire() {
        if (terminal || containerStopped.get()) { terminate(false, false); return; }
        if (!transport.register(this)) {
            fail(503, new IOException("Proxy shutting down"), ProxyResult.Outcome.LOCAL_REJECTION); return;
        }
        try {
            // Время в очереди owner loop входит в deadline. После задержки старта
            // нельзя выдавать запросу новый полный timeout поверх уже прошедшего времени.
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

    private void acquired(Future<Channel> result) {
        synchronized (servletGuard) {
            // isDone() недостаточно: Netty может отложить уведомление listeners.
            // completion ждёт именно обработки результата и возврата полученного канала.
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

    private void acquireFailed(Throwable failure) {
        int status = ProxyFailure.acquireStatus(failure);
        fail(status, failure, status == 503 ? ProxyResult.Outcome.LOCAL_REJECTION : ProxyResult.Outcome.UPSTREAM_FAILURE);
    }

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
            signal(); // Ответ читаем одновременно с upload: upstream может рано вернуть 413/401.
        }
    }

    void signal() {
        // Всегда enqueue: синхронно завершившийся write promise не должен рекурсивно
        // вызывать следующий upload. На exchange допускается только один queued pump.
        if (containerStopped.get() || admissionReleased.get() || !scheduled.compareAndSet(false, true)) return;
        try {
            loop.execute(pump);
        } catch (RejectedExecutionException ignored) { scheduled.set(false); }
    }

    private void runPump() {
        scheduled.set(false);
        synchronized (servletGuard) {
            if (terminal || containerStopped.get() || !initialized) return;
            try { pumpDownload(); pumpUpload(); demand(); }
            catch (IOException failure) { servletIoFailed(failure); }
            catch (Throwable failure) { fail(ProxyFailure.upstreamStatus(failure), failure); }
        }
    }

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
            // Jetty умеет читать в direct ByteBuffer без Servlet default byte[] fallback.
            // NIO transport получает direct payload и не перекладывает его heap → direct.
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
        // Ровно один body buffer in flight; после write им владеет Netty. После
        // promise явно возобновляем pump: нового ReadListener callback может не быть.
        channel.writeAndFlush(new DefaultHttpContent(bytes)).addListener(result -> {
            uploadBusy = false;
            if (!result.isSuccess()) {
                if (!finalResponse) fail(ProxyFailure.upstreamStatus(result.cause()), result.cause());
                return;
            }
            signal();
        });
    }

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

    private void awaitResponseHeaders() {
        synchronized (servletGuard) {
            if (terminal || containerStopped.get() || finalResponse) return;
            // Этот таймер измеряет только молчание upstream после полного upload.
            // Медленный входящий body и backpressure клиента не становятся upstream failure.
            responseHeadersDeadline = loop.schedule(() -> {
                synchronized (servletGuard) {
                    if (!terminal && !containerStopped.get() && !finalResponse)
                        fail(504, new IOException("Upstream response headers timeout"),
                                ProxyResult.Outcome.UPSTREAM_FAILURE);
                }
            }, config.responseHeadersTimeout().toNanos(), TimeUnit.NANOSECONDS);
        }
    }

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
                // HttpClientCodec снимает chunk framing, но не декодирует gzip transfer
                // coding. Нельзя удалять TE и выдавать всё ещё закодированные байты клиенту.
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
                    // Не переиспользуем downstream HTTP/1 с непрочитанным request body.
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
                    // Один socket read может дать много мелких HTTP chunks. Бюджет байтов
                    // ограничивает и число элементов: пустые буферы в очередь не попадают.
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

    private static Map<String, List<String>> headerMap(HttpHeaders headers) {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach(entry -> map.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue()));
        return map;
    }

    private void pumpDownload() throws IOException {
        if (!finalResponse || terminal || containerStopped.get()) return;
        if (downstreamWriter != null) {
            pumpNativeDownload();
            return;
        }
        // Servlet 6.1 может удерживать переданный byte[]. До следующего isReady=true
        // scratch нельзя менять даже после освобождения исходного Netty ByteBuf.
        if (!outputReady()) return;
        while (!responseQueue.isEmpty()) {
            // HEAD/204/пустой ответ вообще не выделяют scratch. Этот массив не pooled:
            // portable Servlet API может удерживать его даже после попытки abort.
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
        // Flush на полученный batch сохраняет задержку коротких streaming/SSE событий.
        if (flushPending) { flushPending = false; output.flush(); }
        if (!outputReady()) return;
        if (responseEnded && !readPending) terminate(true, keepAlive && uploadEnded && !uploadStopped);
    }

    private void pumpNativeDownload() throws IOException {
        if (writerBusy) return;
        while (!responseQueue.isEmpty() && !terminal && !containerStopped.get()) {
            ByteBuf head = responseQueue.peek();
            int count;
            java.nio.ByteBuffer view;
            int batchBytes = coalescedPrefixBytes();
            if (batchBytes > 0) {
                // Мелкие HTTP chunks одного socket read иначе создают callback/write на каждый
                // байт. Копируем минимум два полных мелких фрагмента, уменьшая число write.
                // Крупный/непоместившийся следующий фрагмент остаётся исходным view.
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
                // Contiguous chunks не копируем. Queue и pending write имеют отдельные retain:
                // cancel освобождает queue, но pending view живёт до callback контейнера.
                view = head.nioBuffer(head.readerIndex(), count);
                writingBuffer = head.retain();
                head.skipBytes(count);
                queuedBytes -= count;
                if (!head.isReadable()) responseQueue.remove().release();
            }
            writerBusy = true;
            pendingCleanup++;
            downloaded += count;
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

    private int coalescedPrefixBytes() {
        if (responseQueue.size() < 2 || responseQueue.peek().readableBytes() >= SMALL_FRAGMENT_BYTES) return 0;
        int bytes = 0, fragments = 0;
        // Не ждём новых bytes и не читаем upstream ради batch. Порог 1 KiB оставляет
        // обычные decoder chunks без копии; объединяем только полностью помещающийся prefix.
        for (ByteBuf fragment : responseQueue) {
            int size = fragment.readableBytes();
            if (size >= SMALL_FRAGMENT_BYTES || size > config.chunkBytes() - bytes) break;
            bytes += size;
            fragments++;
        }
        return fragments > 1 ? bytes : 0;
    }

    private void downstreamWriteCompleted(Throwable failure) {
        // Jetty вправе вызвать callback под внутренним lock. Здесь нельзя брать servletGuard:
        // owner pump берёт этот guard ДО обращения к Jetty, что создало бы lock inversion.
        // Ровно одна pending операция позволяет переиспользовать callback и Runnable.
        writeFailure = failure;
        try { loop.execute(completedWrite); }
        catch (RejectedExecutionException ignored) {
            // Shutdown owner loop может опередить поздний callback после abort. Global executor
            // лишь освобождает транспортное владение; Servlet API в этом пути недоступен.
            GlobalEventExecutor.INSTANCE.execute(rejectedCompletedWrite);
        }
    }

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
            // Даже после abort pooled buffer освобождается только здесь. Поздний callback
            // не обращается к recycled Servlet request и лишь завершает cleanup handle.
            if (!ownerAvailable) {
                // NettyTransport.close уже поставил shutdown до остановки loops. Даже если
                // его обработка была прервана, terminate(false, false) не трогает Servlet.
                terminate(false, false);
            } else if (!terminal && !containerStopped.get() && failure != null) servletIoFailed(failure);
            finishCleanupIfReady();
            if (ownerAvailable && !terminal && !containerStopped.get()) signal();
        }
    }

    private void demand() {
        if (terminal || containerStopped.get() || responseEnded || readPending || !responseQueue.isEmpty()) return;
        if (finalResponse && !outputReady()) return;
        readPending = true;
        channel.read();
    }

    private boolean outputReady() {
        // isReady() также может вызвать reentrant onError у Servlet-wrapper. Guard
        // защищает от другого потока, но такой callback требует повторной проверки.
        return !containerStopped.get() && (downstreamWriter != null ? !writerBusy : output.isReady())
                && !containerStopped.get();
    }

    void readComplete() { readPending = false; signal(); }
    void upstreamClosed() {
        keepAlive = false;
        readPending = false;
        if (responseEnded) signal();
        else fail(502, new IOException("Upstream closed before end of response"));
    }

    void fail(int status, Throwable cause) {
        fail(status, cause, ProxyResult.Outcome.UPSTREAM_FAILURE);
    }

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
                    // complete() после усечённого chunked response создал бы успешный end
                    // marker. Abort неблокирующий; guard защищает request от recycle.
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
                // Ошибка контейнера/адаптера не должна оставлять retained buffers и permit.
                terminate(complete, false);
            }
        }
    }

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

    private void cleanupChannel(Channel owned, boolean reusable) {
        // Sentinel не позволяет синхронным Netty listeners объявить cleanup завершённым
        // до регистрации всей цепочки close → release.
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

    private void releaseChannel(Channel owned) {
        observeCleanup(pool.release(owned), result -> {
            if (!result.isSuccess()) closeChannel(owned, null);
        });
    }

    private void closeChannel(Channel owned, java.util.function.Consumer<Future<?>> after) {
        // TLS close может ждать flush close_notify при всё ещё active TCP-канале.
        // До реального closeFuture нельзя возвращать такой канал в ACTIVE pool.
        // Promise отдельного вызова close() не является подтверждением закрытия TCP.
        observeCleanup(owned.closeFuture(), after);
        owned.close();
    }

    private void observeCleanup(Future<?> future, java.util.function.Consumer<Future<?>> after) {
        pendingCleanup++;
        if (future.isDone()) cleanupFinished(future, after);
        else future.addListener(result -> cleanupFinished(result, after));
    }

    private void cleanupFinished(Future<?> result, java.util.function.Consumer<Future<?>> after) {
        synchronized (servletGuard) {
            try { if (after != null) after.accept(result); }
            finally {
                pendingCleanup--;
                finishCleanupIfReady();
            }
        }
    }

    private void finishCleanupIfReady() {
        if (!cleanupPrepared || !acquisitionObserved || pendingCleanup != 0 || completion.isDone()) return;
        // Pending acquire не отменяем: даже после timeout он может вернуть живой канал.
        // Handle и admission остаются заняты до обработки результата, release и close.
        releaseAdmissionOnce();
        metrics.finished(responseStatus, outcome == ProxyResult.Outcome.CANCELLED, uploaded, downloaded,
                System.nanoTime() - startedNanos, peakQueuedBytes);
        completion.complete(new ProxyResult(responseStatus, outcome));
    }

    private void releaseAdmissionOnce() {
        if (admissionReleased.compareAndSet(false, true)) transport.releaseAdmission();
    }

    void shutdown() {
        try {
            loop.execute(() -> fail(503, new IOException("Proxy shutting down"), ProxyResult.Outcome.LOCAL_REJECTION));
        }
        catch (RejectedExecutionException ignored) {
            // Уже поставленная остановка владеет cleanup. Нельзя освобождать permit
            // только по факту отклонённого callback, пока закрытие I/O не подтверждено.
        }
    }
}
