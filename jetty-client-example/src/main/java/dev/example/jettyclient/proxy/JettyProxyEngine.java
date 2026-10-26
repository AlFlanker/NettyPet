package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.ee11.servlet.HttpInput;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.util.Callback;

/**
 * Общий HTTP/1.1 streaming transport на Servlet async и Jetty HttpClient.
 * Получает готовый target от вызывающего Filter или Servlet до начала обмена.
 * Не реализует CONNECT, upgrade, Expect, retries и фильтры Spring Security.
 * Общая библиотека не означает один поток: callbacks сериализуются для каждого обмена.
 */
public final class JettyProxyEngine implements ProxyExchangeExecutor, AutoCloseable {
    private final HttpClient client;
    private final JettyProxyProperties properties;
    private final List<ProxyHeaderCustomizer> customizers;
    private final Semaphore admission;
    private final Set<Exchange> exchanges = ConcurrentHashMap.newKeySet();
    private final Object admissionGuard = new Object();
    private final ScheduledThreadPoolExecutor timer;
    private final ThreadPoolExecutor rewriteExecutor;
    private boolean stopped;

    public JettyProxyEngine(HttpClient client, JettyProxyProperties properties) {
        this(client, properties, List.of());
    }

    public JettyProxyEngine(HttpClient client, JettyProxyProperties properties,
            List<ProxyHeaderCustomizer> customizers) {
        this.client = java.util.Objects.requireNonNull(client);
        this.properties = java.util.Objects.requireNonNull(properties);
        this.customizers = List.copyOf(customizers);
        admission = new Semaphore(properties.maxConcurrent());
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "jetty-example-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        rewriteExecutor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.maxConcurrent()), task -> {
                    Thread thread = new Thread(task, "jetty-example-rewrite");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        rewriteExecutor.allowCoreThreadTimeOut(true);
    }

    /** Включает обмены, ожидающие возврата собственных буферов после отмены. */
    public int activeRequests() { return exchanges.size(); }

    /**
     * Передаёт выбранный запрос upstream. Вызывается ровно один раз до чтения тела
     * и startAsync; после вызова нельзя передавать запрос другому обработчику.
     * Сохраняет переданные wrappers и не останавливает общий HttpClient при close().
     */
    public ProxyCall forward(HttpServletRequest request, HttpServletResponse response, URI target) {
        return forward(request, response, null,
                target == null ? null : ProxyRequestPlan.builder(target).build());
    }

    public ProxyCall forward(HttpServletRequest request, HttpServletResponse response, ProxyRequestPlan plan) {
        return forward(request, response, null, plan);
    }

    /**
     * supplied — async, уже созданный приложением через startAsync(request,response),
     * или null, если engine должен начать async сам. После передачи engine один
     * управляет ответом, deadline и complete; внешний fallback не пишет response.
     */
    @Override
    public ProxyCall forward(HttpServletRequest request, HttpServletResponse response, AsyncContext supplied,
            ProxyRequestPlan plan) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(response, "response");
        AsyncContext owned = null;
        Exchange exchange = null;
        boolean permitAcquired = false;
        try {
            // Establish ownership before a rejection or accessor failure may complete async.
            if (supplied != null) {
                if (supplied.getRequest() != request || supplied.getResponse() != response)
                    return rejectEarly(response, null, 500);
                owned = supplied;
            }
            if (request.getMethod().equals("CONNECT") || request.getHeader("Upgrade") != null
                    || ProxyHeaders.hasToken(request, "Connection", "upgrade"))
                return rejectEarly(response, owned, 501);
            if (request.getHeader("Expect") != null) return rejectEarly(response, owned, 417);
            if (!ProxyHeaders.supportedTransferEncoding(Collections.list(request.getHeaders("Transfer-Encoding"))))
                return rejectEarly(response, owned, 400);
            if (plan == null || !validTarget(plan.target())) return rejectEarly(response, owned, 400);
            if (!request.isAsyncSupported() || (supplied == null ? request.isAsyncStarted() : !request.isAsyncStarted()))
                return rejectEarly(response, owned, 500);
            synchronized (admissionGuard) {
                if (stopped) return rejectEarly(response, owned, 503);
                if (client.getExecutor() == null) return rejectEarly(response, owned, 503);
                if (!admission.tryAcquire()) return rejectEarly(response, owned, 503);
                permitAcquired = true;
                ProxyRequestMetadata metadata = new ProxyRequestMetadata(request.getMethod(), plan.target(),
                        ProxyHeaders.snapshot(request));
                long contentLength = request.getContentLengthLong();
                HttpFields applicationHeaders = ProxyHeaders.snapshot(response);
                // Preserve the actual request/response wrappers supplied by the filter chain.
                if (owned == null) owned = request.startAsync(request, response);
                exchange = new Exchange(request, response, owned, metadata, contentLength, plan, applicationHeaders);
                exchanges.add(exchange);
                // From here Exchange owns the permit, including setup/dispatch failure.
                permitAcquired = false;
                exchange.attachLifecycle();
            }
            exchange.dispatch(exchange::start);
            return exchange.handle;
        } catch (RuntimeException | AssertionError | LinkageError failure) {
            if (exchange != null) {
                exchange.setupFailed(failure);
                return exchange.handle;
            }
            if (permitAcquired) admission.release();
            return rejectEarly(response, owned, 500, failure);
        } catch (Error fatal) {
            if (exchange != null) exchange.setupFailed(fatal);
            else {
                if (permitAcquired) admission.release();
                if (owned != null) {
                    try { owned.complete(); }
                    catch (RuntimeException ignored) { }
                }
            }
            throw fatal;
        }
    }

    private static ProxyCall rejectEarly(HttpServletResponse response, AsyncContext supplied, int status) {
        return rejectEarly(response, supplied, status, new IOException("Proxy rejected request: " + status));
    }

    private static ProxyCall rejectEarly(HttpServletResponse response, AsyncContext supplied, int status, Throwable failure) {
        try { reject(response, status); }
        catch (RuntimeException | AssertionError | LinkageError responseFailure) {
            if (responseFailure != failure) failure.addSuppressed(responseFailure);
        }
        finally {
            if (supplied != null) {
                try { supplied.complete(); }
                catch (IllegalStateException ignored) { }
                catch (RuntimeException completionFailure) {
                    if (completionFailure != failure) failure.addSuppressed(completionFailure);
                    try {
                        HttpServletRequest incoming = (HttpServletRequest)supplied.getRequest();
                        ServletContextRequest.getServletContextRequest(incoming).getServletChannel().abort(failure);
                    } catch (RuntimeException ignored) { }
                }
            }
        }
        CallHandle handle = new CallHandle(null);
        handle.complete(new ProxyExchangeResult(status, failure, 0, 0, Duration.ZERO));
        return handle;
    }

    private static boolean validTarget(URI target) {
        if (target == null || target.isOpaque() || target.getHost() == null
                || target.getRawUserInfo() != null || target.getRawFragment() != null
                || target.getPort() < -1 || target.getPort() == 0 || target.getPort() > 65535) return false;
        String scheme = target.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static void reject(HttpServletResponse response, int status) {
        // A preceding Filter's security/cookie headers survive, while metadata of
        // its earlier representation cannot describe this local empty response.
        for (String name : List.of("Content-Type", "Content-Length", "Content-Encoding", "Content-Range",
                "ETag", "Content-MD5", "Digest", "Content-Digest", "Repr-Digest", "Last-Modified",
                "Accept-Ranges", "Transfer-Encoding", "Trailer", "Expires", "Age")) response.setHeader(name, null);
        response.setStatus(status);
        response.setContentLength(0);
        response.setHeader("Connection", "close");
        if (status >= 400) response.setHeader("Cache-Control", "no-store");
    }

    @Override
    public void close() {
        synchronized (admissionGuard) {
            if (stopped) return;
            stopped = true;
        }
        for (Exchange exchange : exchanges)
            exchange.dispatch(() -> exchange.fail(503, new IOException("Proxy shutting down")));
        timer.shutdownNow();
        // Do not discard queued rewriters: they own borrowed pooled input until
        // the task actually returns, including after an exchange was cancelled.
        rewriteExecutor.shutdown();
    }

    private final class Exchange implements Response.Listener {
        private final HttpServletRequest incoming;
        private final HttpServletResponse outgoing;
        private final AsyncContext async;
        private final ProxyRequestMetadata metadata;
        private final long contentLength;
        private final boolean body;
        private final ProxyRequestPlan plan;
        private final List<ProxyHeaderCustomizer> exchangeCustomizers;
        private final HttpFields applicationHeaders;
        private final CallHandle handle = new CallHandle(cause -> {
            Throwable failure = cause == null ? new CancellationException("Proxy call cancelled") : cause;
            dispatch(() -> fail(503, failure));
        });
        private final Object servletGuard = new Object();
        private final ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<>();
        private final AtomicInteger work = new AtomicInteger();
        private final AtomicInteger retainedChunks = new AtomicInteger();
        private final AtomicBoolean released = new AtomicBoolean();
        private final long started = System.nanoTime();
        private Request upstream;
        private StreamingContent content;
        private ServletInputStream input;
        private Downstream downstream;
        private ScheduledFuture<?> deadline;
        private Content.Chunk pendingChunk;
        private ResponseContentBuffer responseContent;
        private boolean responseDemandPending;
        private boolean responseContentEnded;
        private final Runnable responseReady = this::responseReady;
        private final Runnable responseDemand = () -> dispatch(responseReady);
        private boolean servletAvailable = true;
        private boolean terminal;
        private boolean sent;
        private boolean upstreamDone = true;
        private boolean uploadPending;
        private boolean uploadEnded;
        private boolean uploadStopped;
        private boolean writePending;
        private boolean headersFlushPending;
        private boolean finalHeaders;
        private boolean responseSucceeded;
        private boolean requestSucceeded;
        private boolean noBody;
        private long uploaded;
        private long downloaded;
        private int responseStatus;
        private Throwable terminalFailure;
        private HttpFields preparedResponseHeaders;
        private ProxyResponseMetadata responseMetadata;
        private ProxyResponseBodyPolicy.Selection rewriteSelection;
        private BufferedResponseContent rewriteInput;
        private boolean rewritePending;
        private boolean rewriteStarted;
        private RewriteTask rewriteTask;
        private Iterator<ByteBuffer> rewrittenBuffers;
        private ByteBuffer rewrittenBuffer;

        Exchange(HttpServletRequest incoming, HttpServletResponse outgoing, AsyncContext async,
                ProxyRequestMetadata metadata, long contentLength, ProxyRequestPlan plan, HttpFields applicationHeaders) {
            this.incoming = incoming;
            this.outgoing = outgoing;
            this.async = async;
            this.metadata = metadata;
            this.contentLength = contentLength;
            this.plan = plan;
            this.applicationHeaders = applicationHeaders;
            if (plan.customizers().isEmpty()) exchangeCustomizers = customizers;
            else {
                var combined = new ArrayList<>(customizers);
                combined.addAll(plan.customizers());
                exchangeCustomizers = List.copyOf(combined);
            }
            body = contentLength >= 0 || !metadata.headerValues("Transfer-Encoding").isEmpty();
            uploadEnded = !body;
        }

        /** All callbacks enqueue signals; synchronous completion cannot recurse into another write. */
        void dispatch(Runnable event) {
            events.add(event);
            if (work.getAndIncrement() != 0) return;
            DrainTask task = new DrainTask();
            try { client.getExecutor().execute(task); }
            catch (RejectedExecutionException stoppedExecutor) {
                task.close();
            }
        }

        /** Jetty may accept a queued job and later discard it while stopping its executor. */
        private final class DrainTask implements Runnable, Closeable {
            private final AtomicBoolean claimed = new AtomicBoolean();
            @Override public void run() {
                if (claimed.compareAndSet(false, true)) drain();
            }
            @Override public void close() {
                // Closeable is Jetty ThreadPool.reject's cleanup contract. The same
                // claim also handles executors which both close and throw on reject.
                if (claimed.compareAndSet(false, true))
                    ForkJoinPool.commonPool().execute(Exchange.this::drain);
            }
        }

        private void drain() {
            int remaining = 1;
            do {
                Runnable action;
                while ((action = events.poll()) != null) {
                    synchronized (servletGuard) {
                        try { action.run(); }
                        catch (Throwable failure) { fail(status(failure), failure); }
                        finally { finishCleanup(); }
                    }
                }
                remaining = work.addAndGet(-remaining);
            } while (remaining != 0);
        }

        void start() {
            if (terminal || !servletAvailable) return;
            try {
                Duration timeout = plan.timeout() == null ? properties.exchangeTimeout() : plan.timeout();
                long remaining = timeout.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) { fail(504, new TimeoutException("Exchange deadline")); return; }
                deadline = timer.schedule(() -> dispatch(() -> fail(504, new TimeoutException("Exchange deadline"))),
                        remaining, TimeUnit.NANOSECONDS);
                HttpFields.Mutable requestHeaders = HttpFields.build(ProxyHeaders.request(metadata, exchangeCustomizers));
                // An opaque rewriter cannot validate a cached transformed representation
                // using the origin's representation-specific validators. Keep existence
                // conditions and write preconditions, and preserve explicit Range passthrough.
                if (potentialRewrite() && (metadata.method().equals("GET") || metadata.method().equals("HEAD"))
                        && requestHeaders.getValuesList("Range").isEmpty()) {
                    if (!requestHeaders.getValuesList("If-None-Match").stream().anyMatch(value -> value.trim().equals("*")))
                        requestHeaders.remove("If-None-Match");
                    requestHeaders.remove("If-Modified-Since");
                }
                int localStatus = ProxyHeaders.prepareForwarding(metadata.method(), requestHeaders);
                if (localStatus != 0) {
                    completeLocal(localStatus);
                    return;
                }
                downstream = Downstream.create(incoming, outgoing, properties.chunkBytes(), this::dispatch,
                        failure -> fail(502, failure));
                upstream = client.newRequest(metadata.target()).method(metadata.method()).followRedirects(false)
                        .idleTimeout(properties.idleTimeout().toMillis(), TimeUnit.MILLISECONDS);
                upstream.onRequestSuccess(ignored -> dispatch(() -> requestSucceeded = true));
                upstream.headers(headers -> {
                    for (var field : requestHeaders) headers.add(field);
                });
                if (body) {
                    // The sanitized header copy is the sole source of Content-Type;
                    // do not reintroduce a field excluded by Connection options.
                    content = new StreamingContent(contentLength);
                    upstream.body(content);
                    input = incoming.getInputStream();
                    input.setReadListener(new ReadListener() {
                        @Override public void onDataAvailable() { dispatch(Exchange.this::pumpUpload); }
                        @Override public void onAllDataRead() { dispatch(Exchange.this::pumpUpload); }
                        @Override public void onError(Throwable failure) { dispatch(() -> fail(400, failure)); }
                    });
                }
                sent = true;
                upstreamDone = false;
                try { upstream.send(this); }
                catch (Throwable failure) { upstreamDone = true; throw failure; }
                pumpUpload();
            } catch (Throwable failure) {
                fail(status(failure), failure);
            }
        }

        private void attachLifecycle() {
            synchronized (servletGuard) {
                async.setTimeout(0);
                async.addListener(new AsyncListener() {
                    @Override public void onComplete(AsyncEvent event) { containerFinished(); }
                    @Override public void onError(AsyncEvent event) { containerFinished(); }
                    @Override public void onTimeout(AsyncEvent event) { containerFinished(); }
                    @Override public void onStartAsync(AsyncEvent event) { }
                });
            }
        }

        private void setupFailed(Throwable failure) {
            synchronized (servletGuard) {
                try { fail(500, failure); }
                finally { finishCleanup(); }
            }
        }

        private void pumpUpload() {
            if (terminal || !servletAvailable || !sent || !body || uploadPending || uploadEnded || uploadStopped) return;
            try {
                if (input.isFinished()) { endUpload(); return; }
                if (!input.isReady() || !servletAvailable) return;
                boolean direct = input.getClass() == HttpInput.class;
                RetainableByteBuffer owner = client.getByteBufferPool().acquire(properties.chunkBytes(), direct);
                ByteBuffer buffer = owner.getByteBuffer();
                buffer.clear();
                buffer.limit(properties.chunkBytes());
                int count;
                try {
                    if (direct) {
                        count = input.read(buffer);
                    } else {
                        count = input.read(buffer.array(), buffer.arrayOffset(), buffer.remaining());
                    }
                } catch (Throwable failure) { owner.release(); throw failure; }
                if (!servletAvailable || count <= 0) {
                    owner.release();
                    if (count < 0 && servletAvailable) endUpload();
                    return;
                }
                uploaded += count;
                // Normalize using the read count: Servlet 6.1 specifies a changed
                // limit, while pinned Jetty HttpInput currently advances position.
                buffer.position(0).limit(count);
                if (contentLength >= 0 && uploaded > contentLength) {
                    owner.release();
                    fail(400, new IOException("Content-Length exceeded"));
                    return;
                }
                uploadPending = true;
                Callback callback = once(() -> dispatch(() -> {
                    owner.release();
                    uploadPending = false;
                    pumpUpload();
                }), failure -> dispatch(() -> {
                    owner.release();
                    uploadPending = false;
                    if (!terminal && !uploadStopped) fail(502, failure);
                }));
                // AsyncRequestContent slices this buffer and may mutate position/limit.
                // Only its callback permits returning the memory to the pool.
                try { content.write(buffer, callback); }
                catch (Throwable failure) { callback.failed(failure); }
            } catch (Throwable failure) { fail(400, failure); }
        }

        private void endUpload() {
            if (contentLength >= 0 && uploaded != contentLength) {
                fail(400, new IOException("Incomplete request body"));
                return;
            }
            uploadEnded = true;
            content.close();
        }

        private void completeLocal(int status) {
            terminal = true;
            responseStatus = status;
            if (status >= 400) terminalFailure = new IOException("Proxy rejected request: " + status);
            if (deadline != null) deadline.cancel(false);
            reject(outgoing, status);
            try { async.complete(); }
            catch (IllegalStateException ignored) { }
        }

        @Override public void onHeaders(Response response) {
            int code = response.getStatus();
            if (code == 100 || code == 102 || code == 103) return;
            if (code == 101) {
                // Mark an unsupported upgrade failed before Jetty completes the response.
                // This callback touches no Servlet objects and never waits for the abort.
                IOException failure = new IOException("Upstream protocol upgrade is unsupported");
                response.abort(failure);
                dispatch(() -> fail(502, failure));
                return;
            }
            HttpFields headers = HttpFields.build(response.getHeaders()).asImmutable();
            dispatch(() -> {
                if (terminal || !servletAvailable) return;
                if (code < 200 || code > 599 || code == 101)
                    throw new IllegalStateException("Unsupported upstream status " + code);
                boolean metadataOnly = metadata.method().equals("HEAD") || code == 304;
                if (!metadataOnly && !ProxyHeaders.supportedTransferEncoding(headers.getValuesList("Transfer-Encoding")))
                    throw new IllegalStateException("Unsupported upstream transfer coding");
                if (code == 205 && headers.getLongField("Content-Length") > 0)
                    throw new IllegalStateException("205 response cannot contain content");
                if (finalHeaders) throw new IllegalStateException("Multiple final responses");
                HttpFields responseHeaders = ProxyHeaders.response(metadata, code, headers, exchangeCustomizers);
                finalHeaders = true;
                noBody = metadataOnly || code == 204 || code == 205;
                responseStatus = code;
                preparedResponseHeaders = responseHeaders;
                if (!uploadEnded) {
                    uploadStopped = true;
                }
                if (plan.responseBody() != null && canRewrite(code, headers)) {
                    responseMetadata = new ProxyResponseMetadata(code, snapshot(responseHeaders));
                    try { rewriteSelection = plan.responseBody().select(metadata, responseMetadata); }
                    catch (Exception | AssertionError | LinkageError failure) {
                        throw new ProxyHeaders.CustomizationFailure(failure);
                    }
                    if (rewriteSelection != null) {
                        long length = headers.getLongField("Content-Length");
                        if (length > rewriteSelection.maxInputBytes()) {
                            fail(502, new IOException("Response exceeds rewrite input limit"));
                            return;
                        }
                        rewriteInput = new BufferedResponseContent(client.getByteBufferPool(),
                                properties.chunkBytes(), rewriteSelection.maxInputBytes());
                    }
                }
                if (rewriteSelection == null) {
                    if (metadataOnly && potentialRewrite() && rewriteRepresentationAllowed(code, headers)) {
                        HttpFields.Mutable filtered = HttpFields.build(responseHeaders);
                        removeRepresentationMetadata(filtered);
                        filtered.remove("Content-Length");
                        responseHeaders = filtered.asImmutable();
                    }
                    applyResponseHeaders(responseHeaders);
                }
                pumpDownload();
            });
        }

        private boolean canRewrite(int code, HttpFields headers) {
            return !noBody && rewriteRepresentationAllowed(code, headers);
        }

        private boolean potentialRewrite() {
            return plan.responseBody() != null && !forbidsTransformation(metadata.headerValues("Cache-Control"));
        }

        private boolean rewriteRepresentationAllowed(int code, HttpFields headers) {
            if (code == 206 || headers.contains("Content-Range")) return false;
            List<String> encodings = headers.getValuesList("Content-Encoding");
            if (!encodings.isEmpty() && (encodings.size() != 1 || !encodings.getFirst().trim().equalsIgnoreCase("identity")))
                return false;
            return !forbidsTransformation(metadata.headerValues("Cache-Control"))
                    && !forbidsTransformation(headers.getValuesList("Cache-Control"));
        }

        private boolean forbidsTransformation(List<String> values) {
            for (String directive : new QuotedCSV(true, values.toArray(String[]::new)))
                if (directive.trim().equalsIgnoreCase("no-transform")) return true;
            return false;
        }

        private void removeRepresentationMetadata(HttpFields.Mutable headers) {
            for (String name : List.of("ETag", "Content-MD5", "Digest", "Content-Digest", "Repr-Digest",
                    "Last-Modified", "Accept-Ranges")) headers.remove(name);
        }

        private void applyResponseHeaders(HttpFields headers) {
            outgoing.setStatus(responseStatus);
            ProxyHeaders.applyResponse(headers, outgoing);
            if (responseStatus == 204) outgoing.setHeader("Content-Length", null);
            if (responseStatus == 205) outgoing.setContentLength(0);
            // Do not reuse a downstream connection with an unread request body.
            if (uploadStopped) outgoing.setHeader("Connection", "close");
            headersFlushPending = true;
        }

        @Override public void onContentSource(Response response, Content.Source source) {
            dispatch(() -> {
                if (terminal) {
                    // Network cancellation belongs to this exchange's Request,
                    // not a possibly completed/reused receiver behind an old source.
                    return;
                }
                if (responseContent != null) {
                    throw new IllegalStateException("Multiple response content sources");
                }
                responseContent = new ResponseContentBuffer(source, client.getByteBufferPool(), properties.chunkBytes());
                pumpDownload();
            });
        }

        private void responseReady() {
            responseDemandPending = false;
            pumpDownload();
        }

        private void pumpDownload() {
            if (terminal || !servletAvailable || writePending || !finalHeaders) return;
            if (rewriteSelection != null) {
                pumpRewrite();
                return;
            }
            if (pendingChunk == null && responseContent != null && !responseContentEnded && !responseDemandPending) {
                Content.Chunk chunk = responseContent.read();
                if (chunk == null) {
                    responseDemandPending = true;
                    responseContent.demand(responseDemand);
                } else if (Content.Chunk.isFailure(chunk)) {
                    Throwable failure = chunk.getFailure();
                    chunk.release();
                    fail(status(failure), failure);
                    return;
                } else {
                    responseContentEnded = chunk.isLast();
                    if (!chunk.hasRemaining()) {
                        chunk.release();
                        if (!responseContentEnded) dispatch(this::pumpDownload);
                    } else if (noBody) {
                        chunk.release();
                        fail(502, new IOException("Body on a bodyless response"));
                        return;
                    } else {
                        // read() transfers ownership; no additional retain is needed.
                        retainedChunks.incrementAndGet();
                        pendingChunk = chunk;
                    }
                }
            }
            if (pendingChunk != null) {
                Content.Chunk chunk = pendingChunk;
                pendingChunk = null;
                headersFlushPending = false;
                writePending = true;
                int length = chunk.remaining();
                Callback callback = once(() -> dispatch(() -> {
                    release(chunk);
                    writePending = false;
                    downloaded += length;
                    if (!terminal) pumpDownload();
                }), failure -> dispatch(() -> {
                    release(chunk);
                    writePending = false;
                    if (!terminal) fail(502, failure);
                }));
                try { downstream.write(chunk.getByteBuffer(), callback); }
                catch (Throwable failure) { callback.failed(failure); }
            } else if (headersFlushPending) {
                headersFlushPending = false;
                writePending = true;
                Callback callback = once(() -> dispatch(() -> {
                    writePending = false;
                    pumpDownload();
                }), failure -> dispatch(() -> {
                    writePending = false;
                    if (!terminal) fail(502, failure);
                }));
                try { downstream.flush(callback); }
                catch (Throwable failure) { callback.failed(failure); }
            } else if (responseSucceeded && upstreamDone && responseContentEnded) {
                completeSuccessfully();
            }
        }

        /** Only this opt-in path accumulates a complete response. Streaming never enters it. */
        private void pumpRewrite() {
            if (rewriteStarted) {
                if (rewrittenBuffers != null) pumpRewrittenOutput();
                return;
            }
            if (responseContent != null && !responseContentEnded && !responseDemandPending) {
                Content.Chunk chunk = responseContent.read();
                if (chunk == null) {
                    responseDemandPending = true;
                    responseContent.demand(responseDemand);
                    return;
                }
                try {
                    if (Content.Chunk.isFailure(chunk)) {
                        fail(status(chunk.getFailure()), chunk.getFailure());
                        return;
                    }
                    responseContentEnded = chunk.isLast();
                    rewriteInput.append(chunk.getByteBuffer());
                } catch (IOException failure) {
                    fail(502, failure);
                    return;
                } finally { chunk.release(); }
                // Yield to cancellation/deadline between bounded reads.
                if (!responseContentEnded) {
                    dispatch(this::pumpDownload);
                    return;
                }
            }
            if (!responseContentEnded || !responseSucceeded || !upstreamDone) return;
            rewriteStarted = true;
            ProxyResponseBody inputBody = rewriteInput.body();
            rewritePending = true;
            RewriteTask task = new RewriteTask(inputBody);
            rewriteTask = task;
            try {
                rewriteExecutor.execute(task);
            } catch (RuntimeException | Error failure) {
                // An executor may throw after queueing a job while creating a worker.
                // Release input only if the task has not claimed the right to read it.
                task.cancelQueued();
                throw failure;
            }
        }

        private final class RewriteTask implements Runnable {
            private final ProxyResponseBody inputBody;
            private final AtomicBoolean claimed = new AtomicBoolean();

            RewriteTask(ProxyResponseBody inputBody) { this.inputBody = inputBody; }

            /** Called only by the serialized exchange owner. */
            void cancelQueued() {
                if (!claimed.compareAndSet(false, true)) return;
                rewriteExecutor.remove(this);
                rewriteTask = null;
                rewritePending = false;
            }

            @Override public void run() {
                if (!claimed.compareAndSet(false, true)) return;
                ProxyResponseBody output = null;
                Throwable failure = null;
                try {
                    boolean cancelled;
                    synchronized (servletGuard) { cancelled = terminal; }
                    if (!cancelled) output = java.util.Objects.requireNonNull(
                            rewriteSelection.rewriter().rewrite(responseMetadata, inputBody), "rewrite output");
                } catch (Throwable problem) { failure = problem; }
                ProxyResponseBody transformed = output;
                Throwable problem = failure;
                dispatch(() -> {
                    rewritePending = false;
                    rewriteTask = null;
                    if (terminal) return;
                    if (problem != null) {
                        fail(problem instanceof ProxyResponseBody.TooManyBuffersException ? 502 : 500, problem);
                        return;
                    }
                    if (transformed.length() > rewriteSelection.maxOutputBytes()
                            || transformed.bufferCount() > ProxyResponseBody.MAX_BUFFERS) {
                        fail(502, new IOException("Response exceeds rewrite output limit"));
                        return;
                    }
                    List<ByteBuffer> buffers = transformed.buffers();
                    HttpFields.Mutable headers = HttpFields.build(preparedResponseHeaders);
                    removeRepresentationMetadata(headers);
                    headers.put("Content-Length", Long.toString(transformed.length()));
                    rewrittenBuffers = buffers.iterator();
                    applyResponseHeaders(headers);
                    pumpDownload();
                });
            }
        }

        private void pumpRewrittenOutput() {
            while ((rewrittenBuffer == null || !rewrittenBuffer.hasRemaining()) && rewrittenBuffers.hasNext())
                rewrittenBuffer = rewrittenBuffers.next();
            if (rewrittenBuffer != null && rewrittenBuffer.hasRemaining()) {
                int count = Math.min(rewrittenBuffer.remaining(), properties.chunkBytes());
                ByteBuffer portion = rewrittenBuffer.slice(rewrittenBuffer.position(), count);
                rewrittenBuffer.position(rewrittenBuffer.position() + count);
                headersFlushPending = false;
                writePending = true;
                Callback callback = once(() -> dispatch(() -> {
                    writePending = false;
                    downloaded += count;
                    pumpDownload();
                }), failure -> dispatch(() -> {
                    writePending = false;
                    fail(502, failure);
                }));
                try { downstream.write(portion, callback); }
                catch (Throwable failure) { callback.failed(failure); }
            } else if (headersFlushPending) {
                headersFlushPending = false;
                writePending = true;
                Callback callback = once(() -> dispatch(() -> {
                    writePending = false;
                    pumpDownload();
                }), failure -> dispatch(() -> {
                    writePending = false;
                    fail(502, failure);
                }));
                try { downstream.flush(callback); }
                catch (Throwable failure) { callback.failed(failure); }
            } else completeSuccessfully();
        }

        private void completeSuccessfully() {
            terminal = true;
            if (deadline != null) deadline.cancel(false);
            try { async.complete(); }
            catch (IllegalStateException ignored) { }
        }

        @Override public void onSuccess(Response response) {
            if (response.getStatus() < 200) return;
            dispatch(() -> {
                if (terminal) return;
                responseSucceeded = true;
                if (body && !requestSucceeded) {
                    // Before the final response body is received, Request.fail may also
                    // abort that response. Producer EOF is not sender completion: even
                    // after content.close(), the terminating write may still be pending.
                    upstream.fail(new IOException("Upload stopped by early final response"));
                }
                pumpDownload();
            });
        }

        @Override public void onComplete(Result result) {
            dispatch(() -> {
                upstreamDone = true;
                if (terminal) return;
                boolean usefulResponse = responseSucceeded && result.getResponseFailure() == null;
                if (result.isFailed() && !usefulResponse) fail(status(result.getFailure()), result.getFailure());
                else pumpDownload();
            });
        }

        private void release(Content.Chunk chunk) {
            chunk.release();
            retainedChunks.decrementAndGet();
        }

        private void containerFinished() {
            synchronized (servletGuard) { servletAvailable = false; }
            dispatch(() -> {
                if (!terminal) fail(502, new IOException("Servlet exchange ended"));
            });
        }

        private void fail(int code, Throwable failure) {
            if (terminal) return;
            terminal = true;
            terminalFailure = failure;
            if (responseStatus == 0) responseStatus = code;
            if (deadline != null) deadline.cancel(false);
            if (rewriteTask != null) rewriteTask.cancelQueued();
            if (pendingChunk != null) {
                release(pendingChunk);
                pendingChunk = null;
            }
            if (responseContent != null) responseContent.fail(failure);
            if (content != null) content.fail(failure);
            if (sent && upstream != null) upstream.abort(failure);
            if (downstream != null) downstream.cancel(failure);
            if (!servletAvailable) return;
            try {
                // Query wrappers inside the failure path: a broken accessor must
                // still abort downstream rather than strand a timeout=0 async.
                boolean committed = outgoing.isCommitted();
                if (committed) {
                    servletAvailable = false;
                    abortDownstream(failure);
                } else {
                    responseStatus = code;
                    outgoing.reset();
                    ProxyHeaders.applyResponse(applicationHeaders, outgoing);
                    reject(outgoing, code);
                    async.complete();
                }
            } catch (Throwable ignored) {
                servletAvailable = false;
                try { abortDownstream(failure); }
                catch (Throwable suppressed) { }
            }
        }

        private void abortDownstream(Throwable failure) {
            // complete() on a truncated chunked response would signal a successful EOF.
            ServletContextRequest.getServletContextRequest(incoming).getServletChannel().abort(failure);
        }

        private void finishCleanup() {
            if (terminal && !rewritePending && !writePending && rewriteInput != null) {
                BufferedResponseContent releasing = rewriteInput;
                rewriteInput = null;
                rewrittenBuffers = null;
                rewrittenBuffer = null;
                try { releasing.close(); }
                catch (RuntimeException | Error cleanupFailure) {
                    if (terminalFailure == null) terminalFailure = cleanupFailure;
                    else if (terminalFailure != cleanupFailure) terminalFailure.addSuppressed(cleanupFailure);
                }
            }
            if (terminal && upstreamDone && !uploadPending && !writePending && !rewritePending && retainedChunks.get() == 0
                    && released.compareAndSet(false, true)) {
                exchanges.remove(this);
                admission.release();
                handle.complete(new ProxyExchangeResult(responseStatus, terminalFailure, uploaded, downloaded,
                        Duration.ofNanos(System.nanoTime() - started)));
            }
        }
    }

    /** Completed public handles retain the outcome, not the Servlet exchange and its buffers. */
    private static final class CallHandle implements ProxyCall {
        private final CompletableFuture<ProxyExchangeResult> result = new CompletableFuture<>();
        private final CompletionStage<ProxyExchangeResult> view = result.minimalCompletionStage();
        private final AtomicReference<Consumer<Throwable>> cancellation;

        CallHandle(Consumer<Throwable> cancellation) { this.cancellation = new AtomicReference<>(cancellation); }

        @Override public CompletionStage<ProxyExchangeResult> completion() { return view; }

        @Override public void cancel(Throwable cause) {
            Consumer<Throwable> action = cancellation.get();
            if (action != null) action.accept(cause);
        }

        void complete(ProxyExchangeResult outcome) {
            cancellation.set(null);
            result.complete(outcome);
        }
    }

    private static Map<String, List<String>> snapshot(HttpFields headers) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (var field : headers) values.computeIfAbsent(field.getName(), ignored -> new ArrayList<>()).add(field.getValue());
        return values;
    }

    private static int status(Throwable failure) {
        if (failure instanceof ProxyHeaders.CustomizationFailure) return 500;
        if (failure instanceof TimeoutException) return 504;
        if (failure instanceof RejectedExecutionException) return 503;
        return 502;
    }

    private static Callback once(Runnable success, Consumer<Throwable> failure) {
        return new Callback() {
            private final AtomicBoolean completed = new AtomicBoolean();
            @Override public void succeeded() { if (completed.compareAndSet(false, true)) success.run(); }
            @Override public void failed(Throwable cause) { if (completed.compareAndSet(false, true)) failure.accept(cause); }
            @Override public InvocationType getInvocationType() { return InvocationType.NON_BLOCKING; }
        };
    }

    private static final class StreamingContent extends AsyncRequestContent {
        private final long length;
        StreamingContent(long length) { super((String)null); this.length = length; }
        @Override public long getLength() { return length; }
    }
}
