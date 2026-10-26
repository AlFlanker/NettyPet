package dev.example.jettyclient.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * Bounded загрузка ресурса непосредственно общим Jetty HttpClient, без Servlet,
 * loopback HTTP или поддельного response. Клиент должен быть настроен как proxy client:
 * без общего cookie store, redirect, прозрачного decompression и автоматического auth.
 * close() прекращает свои обмены и workers, но не останавливает предоставленный HttpClient.
 */
public final class ProxyFetchClient implements AutoCloseable {
    @FunctionalInterface
    private interface Action { void run() throws Exception; }
    private final HttpClient client;
    private final JettyProxyProperties properties;
    private final ProxyFetchOptions options;
    private final Semaphore admission;
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final Object admissionGuard = new Object();
    private final ScheduledThreadPoolExecutor timer;
    private final ThreadPoolExecutor decoder;
    private boolean stopped;

    public ProxyFetchClient(HttpClient client, JettyProxyProperties properties) {
        this(client, properties, defaultOptions(properties));
    }

    public ProxyFetchClient(HttpClient client, JettyProxyProperties properties, ProxyFetchOptions options) {
        this.client = Objects.requireNonNull(client, "client");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.options = Objects.requireNonNull(options, "options");
        admission = new Semaphore(options.maxConcurrent());
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "jetty-fetch-deadline");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        decoder = new ThreadPoolExecutor(options.decoderThreads(), options.decoderThreads(), 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(options.maxQueued()), task -> {
                    Thread thread = new Thread(task, "jetty-fetch-decode");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        decoder.allowCoreThreadTimeOut(true);
    }

    private static ProxyFetchOptions defaultOptions(JettyProxyProperties properties) {
        int capacity = Math.min(Objects.requireNonNull(properties, "properties").maxConcurrent(), 8);
        return new ProxyFetchOptions(capacity, 2, capacity);
    }

    public ProxyFetchOptions options() { return options; }

    /** Включает отменённые обмены, пока upstream/decoder ещё владеет их ресурсами. */
    public int activeRequests() { return calls.size(); }

    public ProxyFetchCall fetch(ProxyFetchRequest request) {
        Objects.requireNonNull(request, "request");
        Call call;
        synchronized (admissionGuard) {
            if (stopped) return failed(new IOException("Fetch client is closed"));
            if (client.getExecutor() == null) return failed(new IOException("Jetty HttpClient has no executor"));
            if (!admission.tryAcquire()) return failed(new IOException("Fetch concurrency limit reached"));
            call = new Call(request);
            calls.add(call);
        }
        call.dispatch(call::start);
        return call.handle;
    }

    /**
     * Только для отдельного caller/application worker, не Jetty client executor.
     * Маркированный nonblocking invocation отклоняется. Ожидание не продлевается
     * ради cleanup; timeout/interruption отменяет upstream, а cleanup заканчивается отдельно.
     */
    public ProxyFetchResult fetchBlocking(ProxyFetchRequest request) throws IOException, InterruptedException {
        if (Invocable.isNonBlockingInvocation()) throw new IllegalStateException("Cannot block a nonblocking Jetty invocation");
        long started = System.nanoTime();
        long budget = timeout(request).toNanos();
        ProxyFetchCall call = fetch(request);
        try {
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException("Fetch deadline");
            return call.completion().toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            call.cancel(interrupted);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (TimeoutException timeout) {
            call.cancel(timeout);
            throw timeoutFailure(timeout);
        } catch (CancellationException cancelled) {
            throw new IOException("Fetch cancelled", cancelled);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof TimeoutException timeout) throw timeoutFailure(timeout);
            throw new IOException("Fetch failed", cause);
        }
    }

    private static SocketTimeoutException timeoutFailure(Throwable cause) {
        SocketTimeoutException failure = new SocketTimeoutException("Fetch deadline exceeded");
        failure.initCause(cause);
        return failure;
    }

    private Duration timeout(ProxyFetchRequest request) {
        return request.timeout() == null ? properties.exchangeTimeout() : request.timeout();
    }

    private static ProxyFetchCall failed(Throwable cause) {
        FetchHandle handle = new FetchHandle();
        handle.fail(cause);
        return handle;
    }

    /** Completed public handles retain the result, not the transport/client/pooled input. */
    private static final class FetchHandle implements ProxyFetchCall {
        private final CompletableFuture<ProxyFetchResult> result = new CompletableFuture<>();
        private final CompletionStage<ProxyFetchResult> view = result.minimalCompletionStage();
        private final AtomicReference<Consumer<Throwable>> cancellation = new AtomicReference<>();
        @Override public CompletionStage<ProxyFetchResult> completion() { return view; }
        @Override public void cancel(Throwable cause) {
            Consumer<Throwable> action = cancellation.get();
            if (action != null) action.accept(cause);
        }
        void succeed(ProxyFetchResult answer) {
            cancellation.set(null);
            result.complete(answer);
        }
        void fail(Throwable failure) {
            cancellation.set(null);
            result.completeExceptionally(failure);
        }
        boolean isDone() { return result.isDone(); }
    }

    @Override public void close() {
        synchronized (admissionGuard) {
            if (stopped) return;
            stopped = true;
        }
        for (Call call : calls) call.cancel(new IOException("Fetch client is closing"));
        timer.shutdownNow();
        // Accepted decoder tasks retain borrowed pooled input; let them run cleanup.
        decoder.shutdown();
    }

    private final class Call implements Response.Listener {
        private final ProxyFetchRequest prepared;
        private final ProxyRequestMetadata metadata;
        private final FetchHandle handle = new FetchHandle();
        private final ConcurrentLinkedQueue<Action> events = new ConcurrentLinkedQueue<>();
        private final AtomicInteger work = new AtomicInteger();
        private final long started = System.nanoTime();
        private Request upstream;
        private ScheduledFuture<?> deadline;
        private Content.Source source;
        private BufferedResponseContent input;
        private HttpFields headers;
        private int status;
        private boolean bodyless;
        private boolean sourceEnded;
        private boolean demandPending;
        private boolean responseSucceeded;
        private boolean upstreamDone = true;
        private boolean processing;
        private DecodeTask decodeTask;
        private boolean terminal;
        private boolean released;

        Call(ProxyFetchRequest request) {
            prepared = request;
            metadata = new ProxyRequestMetadata(request.method(), request.target(), request.headers());
            handle.cancellation.set(this::cancel);
        }

        void cancel(Throwable cause) {
            dispatch(() -> fail(cause == null ? new CancellationException("Fetch cancelled") : cause));
        }

        /** No client-executor submission is needed merely to serialize bounded callbacks. */
        void dispatch(Action action) {
            events.add(action);
            if (work.getAndIncrement() != 0) return;
            int remaining = 1;
            do {
                Action event;
                while ((event = events.poll()) != null) {
                    try { event.run(); }
                    catch (Throwable failure) { fail(failure); }
                    finally { cleanup(); }
                }
                remaining = work.addAndGet(-remaining);
            } while (remaining != 0);
        }

        void start() {
            if (terminal) return;
            long remaining = timeout(prepared).toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) { fail(new TimeoutException("Fetch deadline")); return; }
            deadline = timer.schedule(() -> dispatch(() -> fail(new TimeoutException("Fetch deadline"))),
                    remaining, TimeUnit.NANOSECONDS);
            HttpFields.Mutable fields = HttpFields.build(ProxyHeaders.request(metadata, List.of()));
            int localStatus = ProxyHeaders.prepareForwarding(prepared.method(), fields);
            if (localStatus != 0) {
                if (localStatus != 200) { fail(new IOException("Fetch rejected: " + localStatus)); return; }
                terminal = true;
                deadline.cancel(false);
                cleanup();
                handle.succeed(ProxyFetchResult.owned(200, Map.of("content-length", List.of("0")), new byte[0], prepared.maxTextCharacters()));
                return;
            }
            upstream = client.newRequest(prepared.target()).method(prepared.method()).followRedirects(false)
                    .idleTimeout(properties.idleTimeout().toMillis(), TimeUnit.MILLISECONDS);
            upstream.headers(target -> fields.forEach(target::add));
            upstreamDone = false;
            try { upstream.send(this); }
            catch (Throwable failure) { upstreamDone = true; throw failure; }
        }

        @Override public void onHeaders(Response response) {
            int code = response.getStatus();
            if (code < 200 && code != 101) return;
            HttpFields snapshot = HttpFields.build(response.getHeaders()).asImmutable();
            dispatch(() -> {
                if (terminal) return;
                if (headers != null || code < 200 || code > 599) throw new IOException("Invalid fetch response status");
                status = code;
                boolean metadataOnly = prepared.method().equals("HEAD") || code == 304;
                bodyless = metadataOnly || code == 204 || code == 205;
                if (!bodyless && snapshot.getLongField("Content-Length") > prepared.maxWireBytes())
                    throw new IOException("Fetch response exceeds wire limit");
                if (code == 205 && snapshot.getLongField("Content-Length") > 0)
                    throw new IOException("205 response cannot have content");
                if (!metadataOnly && !ProxyHeaders.supportedTransferEncoding(snapshot.getValuesList("Transfer-Encoding")))
                    throw new IOException("Unsupported fetch transfer coding");
                headers = ProxyHeaders.response(metadata, code, snapshot, List.of());
                input = new BufferedResponseContent(client.getByteBufferPool(), properties.chunkBytes(), prepared.maxWireBytes());
                if (bodyless) sourceEnded = true;
                pump();
            });
        }

        @Override public void onContentSource(Response response, Content.Source content) {
            dispatch(() -> {
                if (terminal) return;
                if (source != null) throw new IllegalStateException("Multiple fetch content sources");
                source = content;
                // Even bodyless responses consume their source EOF before completing.
                sourceEnded = false;
                pump();
            });
        }

        void pump() {
            if (terminal || headers == null || processing) return;
            if (source != null && !sourceEnded && !demandPending) {
                Content.Chunk chunk = source.read();
                if (chunk == null) {
                    demandPending = true;
                    source.demand(() -> dispatch(() -> { demandPending = false; pump(); }));
                    return;
                }
                try {
                    if (Content.Chunk.isFailure(chunk)) { fail(chunk.getFailure()); return; }
                    sourceEnded = chunk.isLast();
                    if (bodyless && chunk.hasRemaining()) throw new IOException("Body on a bodyless fetch response");
                    input.append(chunk.getByteBuffer());
                } catch (IOException failure) { fail(failure); return; }
                finally { chunk.release(); }
                if (!sourceEnded) { dispatch(this::pump); return; }
            }
            if (!sourceEnded || !responseSucceeded || !upstreamDone) return;
            ProxyResponseBody body = input.body();
            processing = true;
            DecodeTask task = new DecodeTask(body);
            decodeTask = task;
            try {
                decoder.execute(task);
            } catch (RuntimeException | Error failure) {
                if (task.cancelBeforeStart()) {
                    processing = false;
                    decodeTask = null;
                }
                throw failure;
            }
        }

        /** A cancelled queued task cannot retain input behind other busy decoders. */
        private final class DecodeTask implements Runnable {
            private final ProxyResponseBody body;
            private final AtomicBoolean claimed = new AtomicBoolean();
            DecodeTask(ProxyResponseBody body) { this.body = body; }

            boolean cancelBeforeStart() {
                if (!claimed.compareAndSet(false, true)) return false;
                decoder.remove(this);
                return true;
            }

            @Override public void run() {
                    if (!claimed.compareAndSet(false, true)) return;
                    if (handle.isDone()) { dispatch(this::finished); return; }
                    ProxyFetchResult decoded = null;
                    Throwable problem = null;
                    try {
                        byte[] wire = new byte[Math.toIntExact(body.length())];
                        int offset = 0;
                        for (ByteBuffer part : body.buffers()) {
                            int length = part.remaining();
                            part.get(wire, offset, length);
                            offset += length;
                        }
                        if (handle.isDone()) { dispatch(this::finished); return; }
                        decoded = decode(wire);
                    } catch (Throwable failure) { problem = failure; }
                    ProxyFetchResult answer = decoded;
                    Throwable failure = problem;
                    dispatch(() -> {
                        finished();
                        if (terminal) return;
                        if (failure != null) { fail(failure); return; }
                        terminal = true;
                        deadline.cancel(false);
                        cleanup();
                        handle.succeed(answer);
                    });
            }

            private void finished() {
                processing = false;
                decodeTask = null;
            }
        }

        private ProxyFetchResult decode(byte[] wire) throws IOException {
            Map<String, List<String>> values = snapshot(headers);
            if (!prepared.decodeContentEncoding() || bodyless)
                return ProxyFetchResult.owned(status, values, wire, prepared.maxTextCharacters());
            List<String> encodings = headers.getValuesList("Content-Encoding");
            String encoding = encodings.isEmpty() ? "identity" : encodings.getFirst().trim();
            if (encodings.size() > 1) throw new IOException("Multiple fetch content codings are unsupported");
            byte[] decoded;
            if (encoding.equalsIgnoreCase("identity")) {
                if (wire.length > prepared.maxDecodedBytes()) throw new IOException("Fetch response exceeds decoded limit");
                decoded = wire;
            } else {
                if (status == 206 || headers.contains("Content-Range")) throw new IOException("Cannot decode a partial representation");
                try (InputStream stream = encoding.equalsIgnoreCase("gzip") ? new GZIPInputStream(new ByteArrayInputStream(wire))
                        : encoding.equalsIgnoreCase("deflate") ? new InflaterInputStream(new ByteArrayInputStream(wire))
                        : unsupportedEncoding(encoding)) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(8192, prepared.maxDecodedBytes()));
                    byte[] part = new byte[Math.min(8192, prepared.maxDecodedBytes())];
                    int count;
                    while ((count = stream.read(part)) >= 0) {
                        if (count > prepared.maxDecodedBytes() - output.size()) throw new IOException("Fetch response exceeds decoded limit");
                        output.write(part, 0, count);
                    }
                    decoded = output.toByteArray();
                }
            }
            if (!encoding.equalsIgnoreCase("identity")) {
                values = new LinkedHashMap<>(values);
                for (String name : List.of("content-encoding", "etag", "content-md5", "digest", "content-digest",
                        "repr-digest", "last-modified", "accept-ranges")) values.remove(name);
                values.put("content-length", List.of(Integer.toString(decoded.length)));
            }
            return ProxyFetchResult.owned(status, values, decoded, prepared.maxTextCharacters());
        }

        private InputStream unsupportedEncoding(String encoding) throws IOException {
            throw new IOException("Unsupported fetch content coding: " + encoding);
        }

        @Override public void onSuccess(Response response) {
            if (response.getStatus() < 200) return;
            dispatch(() -> { if (!terminal) { responseSucceeded = true; pump(); } });
        }

        @Override public void onComplete(Result completed) {
            dispatch(() -> {
                upstreamDone = true;
                if (terminal) return;
                boolean usefulResponse = responseSucceeded && completed.getResponseFailure() == null;
                if (completed.isFailed() && !usefulResponse) fail(completed.getFailure());
                else pump();
            });
        }

        void fail(Throwable failure) {
            if (terminal) return;
            terminal = true;
            if (deadline != null) deadline.cancel(false);
            if (decodeTask != null && decodeTask.cancelBeforeStart()) {
                processing = false;
                decodeTask = null;
            }
            if (upstream != null && !upstreamDone) {
                try { upstream.abort(failure); }
                catch (Throwable abortFailure) { if (abortFailure != failure) failure.addSuppressed(abortFailure); }
            }
            handle.fail(failure);
        }

        void cleanup() {
            if (!terminal || processing || released) return;
            Throwable releaseFailure = null;
            if (input != null) {
                BufferedResponseContent owned = input;
                input = null;
                try { owned.close(); }
                catch (RuntimeException | Error failure) { releaseFailure = failure; }
            }
            if (upstreamDone) {
                released = true;
                calls.remove(this);
                admission.release();
            }
            if (releaseFailure != null) handle.fail(releaseFailure);
        }
    }

    private static Map<String, List<String>> snapshot(HttpFields headers) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (var field : headers) values.computeIfAbsent(field.getName().toLowerCase(java.util.Locale.ROOT),
                ignored -> new ArrayList<>()).add(field.getValue());
        return values;
    }
}
