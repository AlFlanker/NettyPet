package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.eclipse.jetty.util.Callback;

/**
 * Самостоятельный HTTP/1.1 streaming-пример на Servlet async и Jetty HttpClient.
 * Не реализует CONNECT, upgrade, Expect, retries и фильтры Spring Security.
 * Общая библиотека не означает один поток: callbacks сериализуются для каждого обмена.
 */
public final class JettyProxyServlet extends HttpServlet {
    private final HttpClient client;
    private final JettyProxyProperties properties;
    private final List<ProxyHeaderCustomizer> customizers;
    private final Semaphore admission;
    private final Set<Exchange> exchanges = ConcurrentHashMap.newKeySet();
    private final Object admissionGuard = new Object();
    private final ScheduledThreadPoolExecutor timer;
    private boolean stopped;

    public JettyProxyServlet(HttpClient client, JettyProxyProperties properties) {
        this(client, properties, List.of());
    }

    public JettyProxyServlet(HttpClient client, JettyProxyProperties properties,
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
    }

    /** Включает обмены, ожидающие возврата собственных буферов после отмены. */
    public int activeRequests() { return exchanges.size(); }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (request.getMethod().equals("CONNECT") || request.getHeader("Upgrade") != null
                || ProxyHeaders.hasToken(request, "Connection", "upgrade")) {
            reject(response, 501);
            return;
        }
        if (request.getHeader("Expect") != null) {
            reject(response, 417);
            return;
        }
        if (!ProxyHeaders.supportedTransferEncoding(Collections.list(request.getHeaders("Transfer-Encoding")))) {
            reject(response, 400);
            return;
        }
        URI target;
        try { target = ProxyHeaders.target(properties.upstream(), request); }
        catch (IllegalArgumentException invalid) { reject(response, 400); return; }
        Exchange exchange;
        synchronized (admissionGuard) {
            if (stopped || !admission.tryAcquire()) { reject(response, 503); return; }
            try {
                ProxyRequestMetadata metadata = new ProxyRequestMetadata(request.getMethod(), target,
                        ProxyHeaders.snapshot(request));
                long contentLength = request.getContentLengthLong();
                // Preserve the actual request/response wrappers supplied by the filter chain.
                AsyncContext async = request.startAsync(request, response);
                exchange = new Exchange(request, response, async, metadata, contentLength);
                exchanges.add(exchange);
            } catch (RuntimeException | Error failure) {
                admission.release();
                throw failure;
            }
            // Listener registration must finish on the original service thread,
            // before the container can complete/recycle an asynchronous request.
            // Once registered in exchanges, only Exchange releases the admission.
            try { exchange.attachLifecycle(); }
            catch (RuntimeException failure) {
                exchange.setupFailed(failure);
                return;
            } catch (Error failure) {
                exchange.setupFailed(failure);
                throw failure;
            }
        }
        exchange.dispatch(exchange::start);
    }

    private static void reject(HttpServletResponse response, int status) {
        response.setStatus(status);
        response.setContentLength(0);
        response.setHeader("Connection", "close");
    }

    @Override
    public void destroy() {
        synchronized (admissionGuard) {
            if (stopped) return;
            stopped = true;
        }
        for (Exchange exchange : exchanges)
            exchange.dispatch(() -> exchange.fail(503, new IOException("Proxy shutting down")));
        timer.shutdownNow();
        super.destroy();
    }

    private final class Exchange implements Response.Listener {
        private final HttpServletRequest incoming;
        private final HttpServletResponse outgoing;
        private final AsyncContext async;
        private final ProxyRequestMetadata metadata;
        private final long contentLength;
        private final boolean body;
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

        Exchange(HttpServletRequest incoming, HttpServletResponse outgoing, AsyncContext async,
                ProxyRequestMetadata metadata, long contentLength) {
            this.incoming = incoming;
            this.outgoing = outgoing;
            this.async = async;
            this.metadata = metadata;
            this.contentLength = contentLength;
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
                long remaining = properties.exchangeTimeout().toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) { fail(504, new TimeoutException("Exchange deadline")); return; }
                deadline = timer.schedule(() -> dispatch(() -> fail(504, new TimeoutException("Exchange deadline"))),
                        remaining, TimeUnit.NANOSECONDS);
                HttpFields.Mutable requestHeaders = HttpFields.build(ProxyHeaders.request(metadata, customizers));
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
                HttpFields responseHeaders = ProxyHeaders.response(metadata, code, headers, customizers);
                finalHeaders = true;
                noBody = metadataOnly || code == 204 || code == 205;
                outgoing.setStatus(code);
                ProxyHeaders.applyResponse(responseHeaders, outgoing);
                if (code == 204) outgoing.setHeader("Content-Length", null);
                if (code == 205) outgoing.setContentLength(0);
                if (!uploadEnded) {
                    uploadStopped = true;
                    // Do not reuse a downstream connection with an unread request body.
                    outgoing.setHeader("Connection", "close");
                }
                headersFlushPending = true;
                pumpDownload();
            });
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
                Callback callback = once(() -> dispatch(() -> {
                    release(chunk);
                    writePending = false;
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
                terminal = true;
                if (deadline != null) deadline.cancel(false);
                try { async.complete(); }
                catch (IllegalStateException ignored) { }
            }
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
            if (deadline != null) deadline.cancel(false);
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
                if (outgoing.isCommitted()) {
                    servletAvailable = false;
                    abortDownstream(failure);
                } else {
                    outgoing.reset();
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
            if (terminal && upstreamDone && !uploadPending && !writePending && retainedChunks.get() == 0
                    && released.compareAndSet(false, true)) {
                exchanges.remove(this);
                admission.release();
            }
        }
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
