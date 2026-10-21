package dev.example.proxy.transport;

import dev.example.proxy.api.*;
import dev.example.proxy.core.*;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Потокобезопасный фасад библиотеки. Один экземпляр на приложение, а не на запрос.
 * Приложение выбирает маршрут; библиотека владеет телами, пулами и завершением обмена.
 * После forward() приложение не должно обращаться к request/response или продолжать filter chain.
 */
public final class ProxyEngine implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ProxyEngine.class);
    private final NettyTransport transport;
    private final ProxyOptions config;
    private final ProcessorPipeline processors;
    private final ProxyMetrics metrics = new ProxyMetrics();

    public ProxyEngine(ProxyOptions config, List<RequestProcessor> processors,
                       DownstreamAborter downstreamAborter) throws Exception {
        this(config, processors, downstreamAborter, ProxyIoAdapter.PORTABLE);
    }

    /** Оптимизированный I/O адаптер задаёт приложение; прежний конструктор переносим. */
    public ProxyEngine(ProxyOptions config, List<RequestProcessor> processors,
                       DownstreamAborter downstreamAborter, ProxyIoAdapter ioAdapter) throws Exception {
        this.config = Objects.requireNonNull(config);
        this.processors = new ProcessorPipeline(processors);
        UpstreamUri.resolve(config.upstream(), "/", null);
        this.transport = new NettyTransport(config, downstreamAborter, ioAdapter);
    }

    /** rawPath — raw origin-form путь без префикса приложения; query читается отдельно. */
    public void forward(HttpServletRequest request, HttpServletResponse response, String rawPath) throws IOException {
        forwardAsync(request, response, rawPath);
    }

    /**
     * Передаёт владение request/response прокси и возвращает управление без ожидания тела.
     * completion подтверждает освобождение ресурсов всего обмена, включая pending acquire;
     * получение response headers ещё не считается завершением streaming-операции.
     * Синхронный отказ подготовки возвращается уже завершённым LOCAL_REJECTION.
     */
    public ProxyCall forwardAsync(HttpServletRequest request, HttpServletResponse response, String rawPath) throws IOException {
        if (request.getMethod().equalsIgnoreCase("CONNECT") || request.getHeader("Upgrade") != null) {
            reject(response, 501); return rejectedCall(501);
        }
        String expect = request.getHeader("Expect");
        if (expect != null && !expect.equalsIgnoreCase("100-continue")) {
            reject(response, 417); return rejectedCall(417);
        }
        if (!transport.tryAcquire()) {
            reject(response, 503);
            return rejectedCall(503);
        }
        AsyncContext async = null;
        ProxyExchange exchange = null;
        try {
            metrics.admitted();
            if (!request.isAsyncSupported() || request.isAsyncStarted()) {
                throw new IllegalStateException("Expected an async-supported initial request");
            }
            IncomingRequest incoming;
            ProxyRequest plan;
            try {
                Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                Collections.list(request.getHeaderNames()).forEach(name ->
                        headers.put(name, Collections.list(request.getHeaders(name))));
                if (!TransferCodings.supported(Collections.list(request.getHeaders("Transfer-Encoding"))))
                    throw new ProxyRejection(501, "Unsupported request transfer coding");
                incoming = new IncomingRequest(request.getMethod(), rawPath,
                        request.getQueryString(), headers, request.getRemoteAddr());
                plan = new ProxyRequest(UpstreamUri.resolve(config.upstream(), incoming.rawPath(), incoming.rawQuery()),
                        HeaderRules.request(headers));
            } catch (IllegalArgumentException malformed) {
                // Некорректные входящие метаданные — ошибка клиента. Ошибка Processor
                // ниже остаётся 500, чтобы не скрывать дефекты прикладного расширения.
                throw new ProxyRejection(400, "Malformed request metadata");
            }
            processors.execute(incoming, plan);
            PreparedRequest prepared = PreparedRequest.capture(request, plan);
            if (!transport.permits(Origin.from(prepared.uri()))) throw new ProxyRejection(403, "Upstream origin not allowed");
            async = request.startAsync(request, response);
            exchange = new ProxyExchange(transport, config, async, request, response, prepared, metrics);
            // После присваивания admission принадлежит exchange, включая сбой start().
            exchange.start();
            return exchange;
        } catch (Exception failure) {
            log.debug("Proxy preparation failed", failure);
            // start() сам завершает уже принадлежащий ему AsyncContext. Повторный
            // reject здесь мог бы обратиться к recycled response после callback.
            if (exchange != null) return exchange;
            int status = failure instanceof ProxyRejection rejection ? rejection.status() : 500;
            try { reject(response, status); }
            finally {
                if (async != null) {
                    try { async.complete(); }
                    catch (IllegalStateException ignored) { /* Container completion won the race. */ }
                }
            }
            return rejectedCall(status);
        } catch (Error fatal) {
            // Ошибки JVM/AssertionError не превращаем в обычный HTTP-ответ. Если async
            // уже начался, закрываем незавершённый ответ: complete() мог бы послать 200.
            if (async != null && exchange == null) {
                try { transport.abortDownstream(request, fatal); }
                catch (Throwable cleanupFailure) {
                    if (cleanupFailure != fatal) fatal.addSuppressed(cleanupFailure);
                }
            }
            throw fatal;
        } finally {
            // finally обязателен: catch(Exception) не освобождает слот при Error из
            // Processor или конструктора exchange. До передачи владения отвечает фасад.
            if (exchange == null) transport.releaseAdmission();
        }
    }

    /** Снимок счётчиков без URI/host-меток: кардинальность не зависит от пользовательских данных. */
    public ProxyMetrics.Snapshot metrics() { return metrics.snapshot(); }

    /** Включает занятые admission-слоты, в том числе отменённые запросы с незавершённым acquire. */
    public int activeRequests() { return transport.activeRequests(); }

    @Override public void close() { transport.close(); }

    private void reject(HttpServletResponse response, int status) {
        metrics.rejected(status);
        response.setStatus(status);
        response.setHeader("Connection", "close");
        response.setContentLength(0);
    }

    private static ProxyCall rejectedCall(int status) {
        return new FinishedCall(CompletableFuture.completedFuture(
                new ProxyResult(status, ProxyResult.Outcome.LOCAL_REJECTION)).minimalCompletionStage());
    }

    private record FinishedCall(CompletionStage<ProxyResult> completion) implements ProxyCall {
        @Override public void cancel(int status) {
            if (status < 400 || status > 599)
                throw new IllegalArgumentException("Cancellation status must be 400..599");
            // Отказ уже сформирован, ресурсов I/O нет. Даже поздняя отмена не трогает Servlet.
        }
    }
}
