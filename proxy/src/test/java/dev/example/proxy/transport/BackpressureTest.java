package dev.example.proxy.transport;

import dev.example.proxy.api.ProxyMetrics;
import dev.example.proxy.api.ProxyOptions;
import dev.example.proxy.api.ProxyResult;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.ScheduledFuture;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Управляемые проверки обратного давления: состояние буферов ОС не даёт устойчивых результатов.
 * Тест начинает работу с уже полученным каналом; получение канала и сам Jetty проверяются
 * интеграционными тестами. Подменённый вывод намеренно удерживает массив до следующего уведомления.
 */
class BackpressureTest {
    @Test
    void responseHeadersDeadlineStartsOnlyAfterTheFinalUploadWriteSucceeds() throws Exception {
        try (Harness h = new Harness()) {
            h.input = new ReadyInput(new byte[Harness.CHUNK]);
            field(h.exchange, "input", h.input);
            h.exchange.signal();
            h.drain();
            assertEquals(1, h.writes.size());
            assertTrue(h.timers.isEmpty(), "Reading or writing request content must not start the headers timer");

            h.completeUpload(0);
            h.drain();
            assertInstanceOf(LastHttpContent.class, h.writes.get(1));
            assertTrue(h.timers.isEmpty(), "The final upload write is still pending");

            h.completeUpload(1);
            assertEquals(1, h.timers.size());
            Timer timer = h.timers.getFirst();
            assertEquals(Duration.ofSeconds(10).toNanos(), timer.delayNanos());
            timer.task().run();
            h.drain();

            verify(h.response).setStatus(504);
            verify(timer.future()).cancel(false);
            assertEquals(new ProxyResult(504, ProxyResult.Outcome.UPSTREAM_FAILURE),
                    h.exchange.completion().toCompletableFuture().join());
        }
    }

    @Test
    void finalHeadersCancelTheirDeadlineWhileInterimHeadersAndStreamingBodyDoNotEndTheCall() throws Exception {
        try (Harness h = new Harness()) {
            h.exchange.signal();
            h.drain();
            h.completeUpload(0);
            h.drain();
            Timer timer = h.timers.getFirst();

            h.exchange.received(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
            h.exchange.received(LastHttpContent.EMPTY_LAST_CONTENT);
            verify(timer.future(), never()).cancel(false);

            h.exchange.received(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
            verify(timer.future()).cancel(false);
            timer.task().run(); // Уже поставленный в очередь таймер ничего не меняет после получения финальных заголовков.
            h.drain();

            verify(h.response, never()).setStatus(504);
            verify(h.transport, never()).releaseAdmission();
            assertFalse(h.exchange.completion().toCompletableFuture().isDone(),
                    "Receiving headers must not finish an ongoing response body");
        }
    }

    @Test
    void lateSuccessfulUploadCompletionCannotArmHeadersTimerAfterEarlyResponseOrCancellation() throws Exception {
        for (boolean earlyResponse : List.of(false, true)) {
            try (Harness h = new Harness()) {
                h.exchange.signal();
                h.drain(); // Последний фрагмент LastHttpContent ещё ожидает завершения записи.
                if (earlyResponse)
                    h.exchange.received(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN));
                else {
                    h.exchange.cancel(503);
                    h.drain();
                }

                h.completeUpload(0);
                h.drain();

                assertTrue(h.timers.isEmpty());
            }
        }
    }

    @Test
    void cancellationCancelsHeadersTimerAndItsLateCallbackCannotChangeTheResult() throws Exception {
        try (Harness h = new Harness()) {
            h.exchange.signal();
            h.drain();
            h.completeUpload(0);
            h.drain();
            Timer timer = h.timers.getFirst();
            h.exchange.cancel(503);
            h.drain();
            verify(timer.future()).cancel(false);

            timer.task().run();

            verify(h.response, never()).setStatus(504);
            assertEquals(new ProxyResult(503, ProxyResult.Outcome.CANCELLED),
                    h.exchange.completion().toCompletableFuture().join());
        }
    }

    @Test
    void callCompletionWaitsForTheLastBodyWriteToBecomeReady() throws Exception {
        try (Harness h = new Harness()) {
            h.downloadMode();
            field(h.exchange, "responseStatus", 200);
            h.receive(repeated((byte) 17));
            h.exchange.received(LastHttpContent.EMPTY_LAST_CONTENT);
            h.drain();

            assertFalse(h.exchange.completion().toCompletableFuture().isDone());
            verify(h.transport, never()).releaseAdmission();
            verify(h.async, never()).complete();

            h.output.completeWrite();
            h.drain();

            assertEquals(new ProxyResult(200, ProxyResult.Outcome.RESPONSE),
                    h.exchange.completion().toCompletableFuture().join());
            verify(h.async).complete();
            verify(h.transport).releaseAdmission();
        }
    }

    @Test
    void cancelledCallCompletesOnlyAfterAsyncCompleteCloseAndPoolRelease() throws Exception {
        try (Harness h = new Harness()) {
            var close = new DefaultChannelPromise(h.channel, ImmediateEventExecutor.INSTANCE);
            var release = new DefaultPromise<Void>(ImmediateEventExecutor.INSTANCE);
            when(h.channel.close()).thenReturn(close);
            when(h.channel.closeFuture()).thenReturn(close);
            when(h.pool.release(h.channel)).thenReturn(release);
            doAnswer(ignored -> {
                assertFalse(h.exchange.completion().toCompletableFuture().isDone(),
                        "Servlet completion must be attempted before publishing the transport result");
                return null;
            }).when(h.async).complete();

            // Отмена отдельной копии CompletableFuture не является сигналом отмены сетевого обмена.
            h.exchange.completion().toCompletableFuture().cancel(true);
            assertFalse(h.exchange.completion().toCompletableFuture().isDone());
            h.exchange.cancel(504);
            h.exchange.cancel(503);
            h.drain();

            verify(h.response).setStatus(504);
            verify(h.response, never()).setStatus(503);
            verify(h.async).complete();
            verify(h.transport, never()).releaseAdmission();
            assertFalse(h.exchange.completion().toCompletableFuture().isDone());

            close.setSuccess();
            assertFalse(h.exchange.completion().toCompletableFuture().isDone());
            release.setSuccess(null);

            assertEquals(new ProxyResult(504, ProxyResult.Outcome.CANCELLED),
                    h.exchange.completion().toCompletableFuture().join());
            verify(h.transport, times(1)).releaseAdmission();
            h.exchange.cancel(502);
            h.drain();
            verify(h.response, never()).setStatus(502);
        }
    }

    @Test
    void nonReusableChannelIsNotReturnedWhileTlsCloseStillLeavesTheTransportActive() throws Exception {
        try (Harness h = new Harness()) {
            var physicallyClosed = new DefaultChannelPromise(h.channel, ImmediateEventExecutor.INSTANCE);
            when(h.channel.closeFuture()).thenReturn(physicallyClosed);
            when(h.channel.isActive()).thenReturn(true);
            // Вызов close может завершиться раньше фактической отправки TLS close_notify.
            assertTrue(h.channel.close().isSuccess());
            clearInvocations(h.channel);

            h.exchange.cancel(504);
            h.drain();

            verify(h.channel).close();
            assertTrue(h.channel.isActive());
            verify(h.pool, never()).release(h.channel);
            verify(h.transport, never()).releaseAdmission();
            assertFalse(h.exchange.completion().toCompletableFuture().isDone());

            when(h.channel.isActive()).thenReturn(false);
            physicallyClosed.setSuccess();

            verify(h.pool, times(1)).release(h.channel);
            verify(h.transport, times(1)).releaseAdmission();
            assertEquals(new ProxyResult(504, ProxyResult.Outcome.CANCELLED),
                    h.exchange.completion().toCompletableFuture().join());
        }
    }

    @Test
    void failedPoolReleaseAlsoWaitsForPhysicalTransportClose() throws Exception {
        try (Harness h = new Harness()) {
            var physicallyClosed = new DefaultChannelPromise(h.channel, ImmediateEventExecutor.INSTANCE);
            when(h.channel.closeFuture()).thenReturn(physicallyClosed);
            when(h.pool.release(h.channel)).thenReturn(
                    ImmediateEventExecutor.INSTANCE.newFailedFuture(new IOException("Pool closed")));
            h.downloadMode();
            field(h.exchange, "keepAlive", true);
            field(h.exchange, "responseStatus", 200);
            h.exchange.received(LastHttpContent.EMPTY_LAST_CONTENT);
            h.drain();

            verify(h.pool).release(h.channel);
            verify(h.channel).close();
            verify(h.transport, never()).releaseAdmission();
            assertFalse(h.exchange.completion().toCompletableFuture().isDone());

            physicallyClosed.setSuccess();

            verify(h.transport, times(1)).releaseAdmission();
            assertEquals(new ProxyResult(200, ProxyResult.Outcome.RESPONSE),
                    h.exchange.completion().toCompletableFuture().join());
        }
    }

    @Test
    void completedAcquireStillHoldsAdmissionUntilItsQueuedListenerReturnsTheChannel() throws Exception {
        try (Harness h = new Harness()) {
            field(h.exchange, "channel", null);
            field(h.exchange, "pool", null);
            when(h.transport.register(h.exchange)).thenReturn(true);
            when(h.transport.pool(any(), eq(h.loop))).thenReturn(h.pool);
            var acquire = new DefaultPromise<Channel>(h.loop);
            var release = new DefaultPromise<Void>(ImmediateEventExecutor.INSTANCE);
            when(h.pool.acquire()).thenReturn(acquire);
            when(h.pool.release(h.channel)).thenReturn(release);
            h.exchange.start();
            h.drain();

            h.exchange.cancel(504); // Ставим отмену в очередь раньше уведомления о результате получения канала.
            acquire.setSuccess(h.channel);
            assertTrue(acquire.isDone());
            h.tasks.remove().run();

            verify(h.async).complete();
            verify(h.transport, never()).releaseAdmission();
            verify(h.pool, never()).release(h.channel);
            assertFalse(h.exchange.completion().toCompletableFuture().isDone());

            h.drain();
            verify(h.pool).release(h.channel);
            assertFalse(h.exchange.completion().toCompletableFuture().isDone());
            release.setSuccess(null);

            assertEquals(new ProxyResult(504, ProxyResult.Outcome.CANCELLED),
                    h.exchange.completion().toCompletableFuture().join());
            verify(h.transport, times(1)).releaseAdmission();
            verify(h.channel, never()).writeAndFlush(any());
        }
    }

    @Test
    void synchronousServletReadOrWriteFailureAbortsAndCountsCancellationInsteadOfBadGateway() throws Exception {
        for (boolean downloading : List.of(false, true)) {
            try (Harness h = new Harness()) {
                IOException ioFailure = new IOException("Downstream I/O failed");
                ByteBuf queued = null;
                if (downloading) {
                    h.downloadMode();
                    ServletOutputStream failedOutput = mock(ServletOutputStream.class);
                    when(failedOutput.isReady()).thenReturn(true);
                    doThrow(ioFailure).when(failedOutput).write(any(byte[].class), anyInt(), anyInt());
                    field(h.exchange, "output", failedOutput);
                    queued = h.receive(repeated((byte) 51));
                } else {
                    ServletInputStream failedInput = mock(ServletInputStream.class);
                    when(failedInput.isReady()).thenReturn(true);
                    when(failedInput.read(any(byte[].class), anyInt(), anyInt())).thenThrow(ioFailure);
                    field(h.exchange, "input", failedInput);
                }

                h.exchange.signal();
                h.drain();

                verify(h.transport, times(1)).abortDownstream(h.request, ioFailure);
                verify(h.transport, times(1)).releaseAdmission();
                verify(h.response, never()).setStatus(502);
                verify(h.async, never()).complete();
                assertEquals(1, h.metrics.snapshot().completed());
                assertEquals(1, h.metrics.snapshot().cancelled());
                assertEquals(0, h.metrics.snapshot().failed());
                if (queued != null) assertEquals(0, queued.refCnt());
            }
        }
    }

    @Test
    void expiredExchangeDeadlineRejectsBeforeRequestingAnyUpstreamConnection() throws Exception {
        try (Harness h = new Harness()) {
            when(h.transport.register(h.exchange)).thenReturn(true);
            field(h.exchange, "startedNanos", System.nanoTime() - Duration.ofSeconds(11).toNanos());
            field(h.exchange, "channel", null);
            field(h.exchange, "pool", null);
            Method acquire = ProxyExchange.class.getDeclaredMethod("acquire");
            acquire.setAccessible(true);

            acquire.invoke(h.exchange);

            verify(h.response).setStatus(504);
            verify(h.transport, never()).pool(any(), any());
            verify(h.transport, times(1)).releaseAdmission();
            verify(h.async).complete();
        }
    }

    @Test
    void servletReadFailureAbortsContextAndReleasesOwnershipEvenWhenAbortAdapterThrows() throws Exception {
        for (boolean abortThrows : List.of(false, true)) {
            try (Harness h = new Harness()) {
                when(h.request.getInputStream()).thenReturn(h.input);
                when(h.response.getOutputStream()).thenReturn(h.output);
                doReturn(new DefaultChannelPromise(h.channel, ImmediateEventExecutor.INSTANCE).setSuccess())
                        .when(h.channel).writeAndFlush(isA(HttpRequest.class));
                Method initialize = ProxyExchange.class.getDeclaredMethod("initialize");
                initialize.setAccessible(true);
                initialize.invoke(h.exchange);
                assertNotNull(h.input.listener);
                h.downloadMode();
                h.receive(repeated((byte) 21));
                ByteBuf queued = h.receive(repeated((byte) 34));
                h.drain();
                assertEquals(1, queued.refCnt());

                IllegalStateException adapterFailure = new IllegalStateException("Abort adapter failed");
                if (abortThrows)
                    doThrow(adapterFailure).when(h.transport).abortDownstream(eq(h.request), any(Throwable.class));
                IOException readFailure = new IOException("Downstream read failed");
                try {
                    h.input.listener.onError(readFailure);
                } catch (IllegalStateException failure) {
                    assertTrue(abortThrows);
                    assertSame(adapterFailure, failure);
                }
                // Ошибка потока требует явного прерывания ответа и не означает завершение AsyncContext.
                verify(h.transport, times(1)).abortDownstream(h.request, readFailure);
                h.input.listener.onError(new IOException("Late duplicate stream failure"));
                h.output.completeWrite();
                h.drain();

                assertEquals(0, queued.refCnt());
                assertEquals(1, h.output.writeCalls, "No Servlet I/O may follow the error callback");
                verify(h.transport, times(1)).releaseAdmission();
                verify(h.pool, times(1)).release(h.channel);
                verify(h.transport, times(1)).abortDownstream(eq(h.request), any(Throwable.class));
                verify(h.async, never()).complete();
            }
        }
    }

    @Test
    void lateSignalsAfterEventLoopShutdownDoNotEscapeIntoServletCallbacks() throws Exception {
        try (Harness h = new Harness()) {
            doThrow(new RejectedExecutionException("Event loop stopped"))
                    .when(h.loop).execute(any(Runnable.class));

            assertDoesNotThrow(h.exchange::signal);
            assertDoesNotThrow(h.exchange::signal);
            assertEquals(0, h.output.writeCalls);
            verify(h.channel, never()).read();
        }
    }

    @Test
    void committedFailureReleasesBuffersAndAdmissionEvenWhenContainerAbortThrows() throws Exception {
        try (Harness h = new Harness()) {
            h.downloadMode();
            byte[] first = repeated((byte) 5);
            h.receive(first);
            ByteBuf queued = h.receive(repeated((byte) 9));
            h.drain();
            assertEquals(1, queued.refCnt());
            when(h.response.isCommitted()).thenReturn(true);
            IllegalStateException abortFailure = new IllegalStateException("Container abort failed");
            doThrow(abortFailure).when(h.transport).abortDownstream(eq(h.request), any(Throwable.class));

            try {
                h.exchange.fail(502, new IOException("Truncated upstream response"));
            } catch (IllegalStateException failure) {
                assertSame(abortFailure, failure);
            }
            assertEquals(0, queued.refCnt());
            assertArrayEquals(first, h.output.pendingBytes());
            verify(h.transport, times(1)).releaseAdmission();
            verify(h.channel, times(1)).close();
            verify(h.pool, times(1)).release(h.channel);
            verify(h.async, never()).complete();

            assertDoesNotThrow(() -> h.exchange.fail(502, new IOException("Late error")));
            verify(h.transport, times(1)).abortDownstream(eq(h.request), any(Throwable.class));
            verify(h.transport, times(1)).releaseAdmission();
        }
    }

    @Test
    void containerCancellationPreventsAlreadyQueuedServletWritePumpFromWritingAgain() throws Exception {
        try (Harness h = new Harness()) {
            List<AsyncListener> listeners = new ArrayList<>();
            doAnswer(invocation -> { listeners.add(invocation.getArgument(0)); return null; })
                    .when(h.async).addListener(any(AsyncListener.class));
            h.exchange.start();
            // Тест уже владеет каналом; само получение канала проверяется интеграционными тестами.
            h.tasks.clear();
            h.downloadMode();
            h.receive(repeated((byte) 13));
            ByteBuf queued = h.receive(repeated((byte) 17));
            h.drain();
            assertEquals(1, h.output.writeCalls);

            h.output.completeWrite(); // Ставим обработку готового вывода в очередь раньше, чем цикл обработает отмену.
            listeners.getFirst().onError(new AsyncEvent(h.async, new IOException("Client disconnected")));
            h.drain();

            assertEquals(1, h.output.writeCalls, "Container cancellation must win over a queued ready pump");
            assertEquals(0, queued.refCnt());
            verify(h.channel, never()).read();
            verify(h.transport, times(1)).releaseAdmission();
            verify(h.async, never()).complete();
        }
    }

    @Test
    void synchronousStartFailureAndQueuedContainerCompletionReleaseAdmissionOnlyOnce() throws Exception {
        try (Harness h = new Harness()) {
            doAnswer(invocation -> {
                AsyncListener listener = invocation.getArgument(0);
                listener.onComplete(new AsyncEvent(h.async));
                throw new IllegalStateException("Container completed during listener registration");
            }).when(h.async).addListener(any(AsyncListener.class));

            assertThrows(IllegalStateException.class, h.exchange::start);
            h.drain();
            verify(h.transport, times(1)).releaseAdmission();
            verify(h.transport, never()).register(any());
        }
    }

    @Test
    void uploadStopsAfterOneInflightWriteAndResumesOnlyWhenChannelCanWrite() throws Exception {
        try (Harness h = new Harness()) {
            h.input = new ReadyInput(new byte[3 * Harness.CHUNK]);
            field(h.exchange, "input", h.input);

            h.exchange.signal();
            h.drain();
            assertEquals(1, h.input.readCalls);
            assertEquals(1, h.writes.size());

            // Входной поток Servlet ещё готов к чтению, но отправляемым буфером уже владеет Netty.
            h.exchange.signal();
            h.drain();
            assertEquals(1, h.input.readCalls);

            h.writable.set(false);
            h.completeUpload(0);
            h.drain();
            assertEquals(1, h.input.readCalls, "Write completion alone must not ignore channel pressure");

            h.writable.set(true);
            h.exchange.signal(); // Имитируем уведомление channelWritabilityChanged о готовности канала к записи.
            h.drain();
            assertEquals(2, h.input.readCalls, "No new servlet callback is required to resume a ready input");
            assertEquals(2, h.writes.size());
        }
    }

    @Test
    void downstreamPressurePreservesScratchAndStopsUpstreamReadsUntilWriteCallback() throws Exception {
        try (Harness h = new Harness()) {
            h.downloadMode();
            byte[] first = repeated((byte) 7);
            byte[] second = repeated((byte) 19);
            ByteBuf firstBuffer = h.receive(first);
            ByteBuf secondBuffer = h.receive(second);

            h.drain();
            assertEquals(1, h.output.writeCalls);
            assertArrayEquals(first, h.output.pendingBytes());
            assertEquals(0, firstBuffer.refCnt(), "Copied Netty content can be released before servlet write completion");
            assertEquals(1, secondBuffer.refCnt(), "Unwritten content stays retained while downstream is blocked");
            verify(h.channel, never()).read();

            // Повторные сигналы сервера и Servlet не должны менять буфер незавершённой записи.
            h.exchange.signal();
            h.exchange.signal();
            h.drain();
            assertArrayEquals(first, h.output.pendingBytes());
            assertEquals(1, h.output.writeCalls);
            verify(h.channel, never()).read();

            h.output.completeWrite();
            h.drain();
            assertEquals(2, h.output.writeCalls);
            assertArrayEquals(second, h.output.pendingBytes());
            assertEquals(0, secondBuffer.refCnt());
            verify(h.channel, never()).read();

            h.output.completeWrite();
            h.drain();
            assertEquals(1, h.output.flushCalls);
            verify(h.channel, times(1)).read();
            byte[] expected = new byte[2 * Harness.CHUNK];
            System.arraycopy(first, 0, expected, 0, first.length);
            System.arraycopy(second, 0, expected, first.length, second.length);
            assertArrayEquals(expected, h.output.completed.toByteArray());

            // Лишнее уведомление не должно запускать бесконечную отправку пустых записей.
            h.exchange.signal();
            h.drain();
            assertEquals(1, h.output.flushCalls);
        }
    }

    @Test
    void cancellationReleasesQueuedBuffersOnceWhilePendingServletWriteKeepsItsArray() throws Exception {
        try (Harness h = new Harness()) {
            h.downloadMode();
            byte[] first = repeated((byte) 31);
            ByteBuf firstBuffer = h.receive(first);
            ByteBuf queued = h.receive(repeated((byte) 42));
            h.drain();
            assertEquals(0, firstBuffer.refCnt());
            assertEquals(1, queued.refCnt());

            h.cancel();
            h.cancel();
            assertEquals(0, queued.refCnt());
            assertArrayEquals(first, h.output.pendingBytes(), "Cancellation must not recycle in-use servlet scratch");
            verify(h.transport, times(1)).releaseAdmission();
            verify(h.channel, times(1)).close();
            verify(h.pool, times(1)).release(h.channel);

            h.output.completeWrite(); // Поздняя запись после отмены должна завершить очистку, не возобновляя передачу.
            h.drain();
            assertEquals(1, h.output.writeCalls);
            verify(h.channel, never()).read();
        }
    }

    @Test
    void responseQueueAcceptsTheExactPayloadBudgetButRejectsTheNextByteWithoutRetainingIt() throws Exception {
        try (Harness h = new Harness()) {
            h.nativeMode();
            List<ByteBuf> accepted = new ArrayList<>();
            for (int i = 0; i < 4; i++) accepted.add(h.receive(repeated((byte) i)));
            assertEquals(4 * Harness.CHUNK, field(h.exchange, "queuedBytes"));
            assertEquals(4 * Harness.CHUNK, field(h.exchange, "peakQueuedBytes"));
            accepted.forEach(buffer -> assertEquals(1, buffer.refCnt()));

            ByteBuf extra = Unpooled.wrappedBuffer(new byte[] {42});
            HttpContent content = new DefaultHttpContent(extra);
            IOException overflow;
            try {
                overflow = assertThrows(IOException.class, () -> h.exchange.received(content));
                assertEquals(1, extra.refCnt(), "Rejected payload must not acquire queue ownership");
                assertEquals(4 * Harness.CHUNK, field(h.exchange, "queuedBytes"));
            } finally {
                content.release(); // NettyTransport.Inbound освобождает каждое сообщение, полученное от декодера.
            }

            h.exchange.fail(502, overflow); // Адаптер преобразует ошибку приёма в завершение обмена с очисткой ресурсов.
            h.drain();

            assertEquals(0, extra.refCnt());
            accepted.forEach(buffer -> assertEquals(0, buffer.refCnt()));
            assertEquals(0, field(h.exchange, "queuedBytes"));
            assertEquals(new ProxyResult(502, ProxyResult.Outcome.UPSTREAM_FAILURE),
                    h.exchange.completion().toCompletableFuture().join());
            assertEquals(4 * Harness.CHUNK, h.metrics.snapshot().maxQueuedResponseBytes());
            verify(h.response).setStatus(502);
            verify(h.transport, times(1)).releaseAdmission();
            verify(h.channel, never()).read();
            assertEquals(0, h.nativeOutput.writeCalls, "A queued pump must not publish the rejected batch");
        }
    }

    @Test
    void emptyDecoderContentsDoNotConsumeQueueEntriesOrCreateNativeWrites() throws Exception {
        try (Harness h = new Harness()) {
            h.nativeMode();
            for (int i = 0; i < 4096; i++) {
                HttpContent empty = new DefaultHttpContent(Unpooled.EMPTY_BUFFER);
                try { h.exchange.received(empty); }
                finally { empty.release(); }
            }

            assertTrue(((ArrayDeque<?>) field(h.exchange, "responseQueue")).isEmpty());
            assertEquals(0, field(h.exchange, "queuedBytes"));
            assertEquals(0, field(h.exchange, "peakQueuedBytes"));
            assertEquals(1, h.tasks.size(), "Signals must collapse to a single queued pump");
            h.drain();
            assertEquals(0, h.nativeOutput.writeCalls);
            assertEquals(0, h.nativeOutput.flushCalls);
            verify(h.channel, times(1)).read();

            ByteBuf body = h.receive(new byte[] {17});
            h.drain();
            assertArrayEquals(new byte[] {17}, h.nativeOutput.pendingBytes());
            assertEquals(1, body.refCnt());
            assertEquals(1, h.nativeOutput.writeCalls, "A real short event must still be delivered immediately");
        }
    }

    @Test
    void directUploadReadsIntoTheSameDirectBufferOwnedByNetty() throws Exception {
        try (Harness h = new Harness()) {
            byte[] body = repeated((byte) 63);
            ByteBufferInput input = new ByteBufferInput(body);
            field(h.exchange, "input", input);
            field(h.exchange, "directInput", true);

            h.exchange.signal();
            h.drain();

            assertEquals(1, input.readCalls);
            ByteBuf outgoing = h.writes.getFirst().content();
            assertTrue(outgoing.isDirect());
            byte[] actual = new byte[outgoing.readableBytes()];
            outgoing.getBytes(outgoing.readerIndex(), actual);
            assertArrayEquals(body, actual);
            h.exchange.signal();
            h.drain();
            assertEquals(1, input.readCalls, "Direct input must retain the one-inflight upload limit");
        }
    }

    @Test
    void portableEmptyResponseDoesNotAllocateBodyScratch() throws Exception {
        try (Harness h = new Harness()) {
            h.downloadMode();
            h.exchange.received(LastHttpContent.EMPTY_LAST_CONTENT);
            h.drain();

            assertNull(field(h.exchange, "outputScratch"));
            assertEquals(0, h.output.writeCalls);
            assertTrue(h.exchange.completion().toCompletableFuture().isDone());
        }
    }

    @Test
    void nativeWriteSharesPayloadAndWaitsForEveryWriteAndFlushBeforeReadingAgain() throws Exception {
        try (Harness h = new Harness()) {
            h.nativeMode();
            byte[] first = repeated((byte) 7);
            byte[] second = repeated((byte) 19);
            ByteBuf firstBuffer = h.receive(first);
            ByteBuf secondBuffer = h.receive(second);
            h.drain();

            assertSame(first, h.nativeOutput.pending.array(), "The view must share the decoder payload");
            assertArrayEquals(first, h.nativeOutput.pendingBytes());
            assertEquals(1, firstBuffer.refCnt(), "A native write still owns its original pooled storage");
            assertEquals(1, secondBuffer.refCnt());
            assertNull(field(h.exchange, "outputScratch"));
            h.exchange.signal();
            h.drain();
            assertEquals(1, h.nativeOutput.writeCalls);
            verify(h.channel, never()).read();

            h.nativeOutput.complete(null);
            assertEquals(1, firstBuffer.refCnt(), "Completion is handed to the owner loop first");
            h.drain();
            assertEquals(0, firstBuffer.refCnt());
            assertEquals(2, h.nativeOutput.writeCalls);
            assertArrayEquals(second, h.nativeOutput.pendingBytes());
            verify(h.channel, never()).read();

            h.nativeOutput.complete(null);
            h.drain();
            assertEquals(0, secondBuffer.refCnt());
            assertEquals(1, h.nativeOutput.flushCalls);
            verify(h.channel, never()).read();
            h.nativeOutput.complete(null);
            h.drain();
            verify(h.channel, times(1)).read();
            assertEquals(0, h.output.writeCalls);
        }
    }

    @Test
    void nativeFinalBodyAndFlushBothHoldCompletionAndAdmission() throws Exception {
        try (Harness h = new Harness()) {
            h.nativeMode();
            field(h.exchange, "responseStatus", 200);
            ByteBuf body = h.receive(repeated((byte) 17));
            h.exchange.received(LastHttpContent.EMPTY_LAST_CONTENT);
            h.drain();

            verify(h.async, never()).complete();
            verify(h.transport, never()).releaseAdmission();
            assertFalse(h.exchange.completion().toCompletableFuture().isDone());
            h.nativeOutput.complete(null);
            h.drain();
            assertEquals(0, body.refCnt());
            assertEquals(1, h.nativeOutput.flushCalls);
            verify(h.async, never()).complete();
            verify(h.transport, never()).releaseAdmission();

            h.nativeOutput.complete(null);
            h.drain();
            assertEquals(new ProxyResult(200, ProxyResult.Outcome.RESPONSE),
                    h.exchange.completion().toCompletableFuture().join());
            verify(h.async).complete();
            verify(h.transport).releaseAdmission();
        }
    }

    @Test
    void nativeCancellationRetainsInflightBufferUntilLateSuccessOrFailureCallback() throws Exception {
        for (Throwable lateFailure : Arrays.asList(null, new IOException("Aborted native write"))) {
            try (Harness h = new Harness()) {
                h.nativeMode();
                byte[] first = repeated((byte) 31);
                ByteBuf writing = h.receive(first);
                ByteBuf queued = h.receive(repeated((byte) 42));
                h.drain();
                h.cancel();
                h.cancel();

                assertEquals(0, queued.refCnt());
                assertEquals(1, writing.refCnt());
                assertArrayEquals(first, h.nativeOutput.pendingBytes());
                verify(h.transport, never()).releaseAdmission();
                assertFalse(h.exchange.completion().toCompletableFuture().isDone());
                clearInvocations(h.request, h.response, h.async);

                h.nativeOutput.complete(lateFailure);
                h.drain();
                assertEquals(0, writing.refCnt());
                assertTrue(h.exchange.completion().toCompletableFuture().isDone());
                verify(h.transport, times(1)).releaseAdmission();
                verify(h.channel, never()).read();
                verifyNoInteractions(h.request, h.response, h.async);
                assertEquals(1, h.nativeOutput.writeCalls);
                assertEquals(0, h.nativeOutput.flushCalls);
            }
        }
    }

    @Test
    void nativeWriteFailureAbortsAndReleasesQueuedOwnership() throws Exception {
        try (Harness h = new Harness()) {
            h.nativeMode();
            ByteBuf writing = h.receive(repeated((byte) 25));
            ByteBuf queued = h.receive(repeated((byte) 67));
            h.drain();
            IOException failure = new IOException("Client disconnected during native write");
            h.nativeOutput.complete(failure);
            h.drain();

            assertEquals(0, writing.refCnt());
            assertEquals(0, queued.refCnt());
            verify(h.transport).abortDownstream(h.request, failure);
            verify(h.transport).releaseAdmission();
            verify(h.async, never()).complete();
            assertEquals(ProxyResult.Outcome.CANCELLED,
                    h.exchange.completion().toCompletableFuture().join().outcome());
        }
    }

    @Test
    void nativeCallbackNeverTakesServletGuardOnContainerThread() throws Exception {
        try (Harness h = new Harness()) {
            h.nativeMode();
            h.receive(repeated((byte) 8));
            h.drain();
            Thread containerCallback = new Thread(() -> h.nativeOutput.complete(null));
            // Имитируем обратный вызов Jetty под его блокировкой, пока обработчик обмена удерживает servletGuard.
            synchronized (field(h.exchange, "servletGuard")) {
                containerCallback.start();
                containerCallback.join(2000);
                assertFalse(containerCallback.isAlive(), "Native callback must only enqueue, never enter servletGuard");
            }
            h.drain();
        }
    }

    @Test
    void lateNativeCallbackAfterOwnerShutdownStillReleasesTheBufferAndAdmission() throws Exception {
        try (Harness h = new Harness()) {
            h.nativeMode();
            ByteBuf body = h.receive(repeated((byte) 91));
            h.drain();
            h.cancel();
            assertEquals(1, body.refCnt());
            doThrow(new RejectedExecutionException("Owner loop terminated"))
                    .when(h.loop).execute(any(Runnable.class));
            clearInvocations(h.request, h.response, h.async);

            h.nativeOutput.complete(new IOException("Late abort callback"));
            h.exchange.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(0, body.refCnt());
            verify(h.transport, times(1)).releaseAdmission();
            verifyNoInteractions(h.request, h.response, h.async);
        }
    }

    @Test
    void inlineNativeCallbacksAreQueuedBeforeTheNextWrite() throws Exception {
        try (Harness h = new Harness()) {
            h.downloadMode();
            int[] writes = {0};
            field(h.exchange, "downstreamWriter", new DownstreamWriter() {
                @Override public void write(ByteBuffer buffer, Completion completion) {
                    writes[0]++;
                    completion.complete(null);
                    assertEquals(1, h.tasks.size(), "Inline completion must enqueue one owner task");
                }
                @Override public void flush(Completion completion) { completion.complete(null); }
            });
            ByteBuf first = h.receive(repeated((byte) 1));
            ByteBuf second = h.receive(repeated((byte) 2));
            h.exchange.received(LastHttpContent.EMPTY_LAST_CONTENT);
            h.drain();

            assertEquals(2, writes[0]);
            assertEquals(0, first.refCnt());
            assertEquals(0, second.refCnt());
            assertTrue(h.exchange.completion().toCompletableFuture().isDone());
        }
    }

    @Test
    void nativeCoalescingCombinesOnlyCompleteSmallFragmentsAndPreservesLargeQueueHead() throws Exception {
        try (Harness h = new Harness()) {
            h.nativeMode();
            byte[] first = {1, 2, 3};
            byte[] second = {4, 5};
            byte[] third = new byte[Harness.CHUNK + 17];
            for (int i = 0; i < third.length; i++) third[i] = (byte) i;
            ByteBuf firstBuffer = h.receive(first);
            ByteBuf secondBuffer = h.receive(second);
            ByteBuf thirdBuffer = h.receive(third);
            byte[] expected = new byte[first.length + second.length];
            System.arraycopy(first, 0, expected, 0, first.length);
            System.arraycopy(second, 0, expected, first.length, second.length);
            h.drain();

            assertEquals(1, h.nativeOutput.writeCalls);
            assertTrue(h.nativeOutput.pending.isDirect());
            assertEquals(expected.length, h.nativeOutput.pending.remaining());
            assertArrayEquals(expected, h.nativeOutput.pendingBytes());
            assertEquals(0, firstBuffer.refCnt());
            assertEquals(0, secondBuffer.refCnt());
            assertEquals(1, thirdBuffer.refCnt(), "The large fragment still belongs to the queue");
            assertEquals(0, thirdBuffer.readerIndex(), "Coalescing must not consume part of the large fragment");
            assertEquals(third.length, field(h.exchange, "queuedBytes"));
            ByteBuf batch = (ByteBuf) field(h.exchange, "writingBuffer");
            assertTrue(batch.capacity() <= Harness.CHUNK, "Coalescing cannot grow with response size");
            verify(h.channel, never()).read();

            h.nativeOutput.complete(null);
            h.drain();
            assertEquals(0, batch.refCnt());
            assertEquals(2, h.nativeOutput.writeCalls);
            assertSame(third, h.nativeOutput.pending.array(), "The large fragment uses its original view");
            assertArrayEquals(Arrays.copyOf(third, Harness.CHUNK), h.nativeOutput.pendingBytes());
            assertEquals(third.length - Harness.CHUNK, field(h.exchange, "queuedBytes"));
            h.nativeOutput.complete(null);
            h.drain();
            assertSame(third, h.nativeOutput.pending.array());
            assertArrayEquals(Arrays.copyOfRange(third, Harness.CHUNK, third.length),
                    h.nativeOutput.pendingBytes());
            h.nativeOutput.complete(null);
            h.drain();
            assertEquals(0, thirdBuffer.refCnt());
        }
    }

    @Test
    void nativeCoalescedBufferSurvivesCancellationUntilItsWriteCallback() throws Exception {
        try (Harness h = new Harness()) {
            h.nativeMode();
            ByteBuf first = h.receive(new byte[] {1});
            ByteBuf tiny = h.receive(new byte[] {2});
            ByteBuf second = h.receive(new byte[2 * Harness.CHUNK]);
            h.drain();
            ByteBuf batch = (ByteBuf) field(h.exchange, "writingBuffer");
            assertEquals(0, first.refCnt());
            assertEquals(0, tiny.refCnt());
            assertTrue(batch.isDirect());
            assertEquals(1, second.refCnt());

            h.cancel();
            assertEquals(0, second.refCnt());
            assertEquals(1, batch.refCnt());
            assertEquals(2, h.nativeOutput.pending.remaining());
            verify(h.transport, never()).releaseAdmission();
            h.nativeOutput.complete(new IOException("Cancelled coalesced write"));
            h.drain();
            assertEquals(0, batch.refCnt());
            verify(h.transport).releaseAdmission();
        }
    }

    @Test
    void nativeCoalescingStopsAtBudgetWithoutCopyingAPartialFragment() throws Exception {
        try (Harness h = new Harness()) {
            h.nativeMode();
            byte[] first = new byte[400];
            byte[] second = new byte[400];
            byte[] third = new byte[400];
            Arrays.fill(first, (byte) 1);
            Arrays.fill(second, (byte) 2);
            Arrays.fill(third, (byte) 3);
            h.receive(first);
            h.receive(second);
            ByteBuf remainder = h.receive(third);
            h.drain();

            assertEquals(800, h.nativeOutput.pending.remaining());
            assertEquals(400, field(h.exchange, "queuedBytes"));
            assertEquals(0, remainder.readerIndex());
            byte[] expected = new byte[800];
            System.arraycopy(first, 0, expected, 0, 400);
            System.arraycopy(second, 0, expected, 400, 400);
            assertArrayEquals(expected, h.nativeOutput.pendingBytes());

            h.nativeOutput.complete(null);
            h.drain();
            assertSame(third, h.nativeOutput.pending.array());
            assertArrayEquals(third, h.nativeOutput.pendingBytes());
            assertEquals(2, h.nativeOutput.writeCalls, "Three source fragments need only two native writes");
        }
    }

    @Test
    void singleTinyFragmentUsesItsOriginalViewWithoutWaitingForABatch() throws Exception {
        try (Harness h = new Harness()) {
            h.nativeMode();
            byte[] event = {42};
            ByteBuf body = h.receive(event);
            h.drain();

            assertEquals(1, h.nativeOutput.writeCalls);
            assertSame(event, h.nativeOutput.pending.array());
            assertSame(body, field(h.exchange, "writingBuffer"));
            assertEquals(1, body.refCnt());
            assertNull(field(h.exchange, "outputScratch"));
        }
    }

    private static byte[] repeated(byte value) {
        byte[] bytes = new byte[Harness.CHUNK];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static void field(Object instance, String name, Object value) throws ReflectiveOperationException {
        Field field = ProxyExchange.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        Field field = ProxyExchange.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static final class Harness implements AutoCloseable {
        static final int CHUNK = 1024;
        final NettyTransport transport = mock(NettyTransport.class);
        final EventLoop loop = mock(EventLoop.class);
        final Channel channel = mock(Channel.class);
        final FixedChannelPool pool = mock(FixedChannelPool.class);
        final AsyncContext async = mock(AsyncContext.class);
        final HttpServletRequest request = mock(HttpServletRequest.class);
        final HttpServletResponse response = mock(HttpServletResponse.class);
        final AtomicBoolean writable = new AtomicBoolean(true);
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<Timer> timers = new ArrayList<>();
        final List<HttpContent> writes = new ArrayList<>();
        final List<DefaultChannelPromise> writePromises = new ArrayList<>();
        final HoldingOutput output = new HoldingOutput();
        final ProxyMetrics metrics = new ProxyMetrics();
        final ProxyExchange exchange;
        ReadyInput input = new ReadyInput(new byte[0]);
        HoldingWriter nativeOutput;

        Harness() throws Exception {
            when(transport.nextLoop()).thenReturn(loop);
            doAnswer(invocation -> { tasks.add(invocation.getArgument(0)); return null; })
                    .when(loop).execute(any(Runnable.class));
            doAnswer(invocation -> {
                ScheduledFuture<?> future = mock(ScheduledFuture.class);
                TimeUnit unit = invocation.getArgument(2);
                timers.add(new Timer(invocation.getArgument(0), unit.toNanos(invocation.getArgument(1)), future));
                return future;
            }).when(loop).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
            when(channel.alloc()).thenReturn(UnpooledByteBufAllocator.DEFAULT);
            when(channel.isWritable()).thenAnswer(ignored -> writable.get());
            when(channel.read()).thenReturn(channel);
            var closed = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE).setSuccess();
            when(channel.close()).thenReturn(closed);
            when(channel.closeFuture()).thenReturn(closed);
            when(pool.release(channel)).thenReturn(ImmediateEventExecutor.INSTANCE.newSucceededFuture(null));
            DefaultAttributeMap attributes = new DefaultAttributeMap();
            when(channel.attr(NettyTransport.EXCHANGE)).thenReturn(attributes.attr(NettyTransport.EXCHANGE));
            when(channel.writeAndFlush(any())).thenAnswer(invocation -> {
                writes.add(invocation.getArgument(0));
                var promise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
                writePromises.add(promise);
                return promise;
            });

            when(request.getContentLengthLong()).thenReturn(-1L);
            var properties = new ProxyOptions(URI.create("http://localhost:9000"), 1, 8, 4, 4,
                    CHUNK, CHUNK * 4, Duration.ofSeconds(1), Duration.ofSeconds(1),
                    Duration.ofSeconds(10), Duration.ofSeconds(5), List.of());
            exchange = new ProxyExchange(transport, properties, async, request, response,
                    new PreparedRequest("POST", properties.upstream(), Map.of(), -1L, false), metrics);
            field(exchange, "channel", channel);
            field(exchange, "pool", pool);
            field(exchange, "input", input);
            field(exchange, "output", output);
            field(exchange, "initialized", true);
            field(exchange, "headersSent", true);
            output.setWriteListener(new WriteListener() {
                @Override public void onWritePossible() { exchange.signal(); }
                @Override public void onError(Throwable throwable) { fail(throwable); }
            });
        }

        void downloadMode() throws Exception {
            field(exchange, "uploadEnded", true);
            field(exchange, "finalResponse", true);
        }

        void nativeMode() throws Exception {
            downloadMode();
            nativeOutput = new HoldingWriter();
            field(exchange, "downstreamWriter", nativeOutput);
            field(exchange, "output", null);
        }

        ByteBuf receive(byte[] bytes) throws IOException {
            ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
            HttpContent content = new DefaultHttpContent(buffer);
            try { exchange.received(content); }
            finally { content.release(); } // Соблюдаем тот же порядок владения и освобождения, что и NettyTransport.Inbound.
            return buffer;
        }

        void drain() {
            int budget = 100;
            while (!tasks.isEmpty()) {
                assertTrue(budget-- > 0, "Signal pump did not quiesce");
                tasks.remove().run();
            }
        }

        void completeUpload(int index) {
            ReferenceCountUtil.release(writes.get(index)); // Имитируем освобождение буфера, которым владела исходящая запись Netty.
            writePromises.get(index).setSuccess();
        }

        void cancel() throws Exception {
            Method method = ProxyExchange.class.getDeclaredMethod("terminate", boolean.class, boolean.class);
            method.setAccessible(true);
            method.invoke(exchange, false, false);
        }

        @Override public void close() throws Exception {
            cancel();
            if (nativeOutput != null && nativeOutput.completion != null) {
                nativeOutput.complete(new IOException("Fixture closing"));
                drain();
            }
            for (HttpContent content : writes) {
                if (content.refCnt() > 0) content.release();
            }
        }
    }

    private record Timer(Runnable task, long delayNanos, ScheduledFuture<?> future) { }

    private static final class ReadyInput extends ServletInputStream {
        private final byte[] bytes;
        private int offset;
        int readCalls;
        ReadListener listener;

        ReadyInput(byte[] bytes) { this.bytes = bytes; }
        @Override public boolean isFinished() { return offset == bytes.length; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { this.listener = listener; }
        @Override public int read() { throw new AssertionError("Bulk input expected"); }
        @Override public int read(byte[] target, int start, int length) {
            readCalls++;
            if (isFinished()) return -1;
            int count = Math.min(length, bytes.length - offset);
            System.arraycopy(bytes, offset, target, start, count);
            offset += count;
            return count;
        }
    }

    private static final class ByteBufferInput extends ServletInputStream {
        private final ByteBuffer source;
        int readCalls;

        ByteBufferInput(byte[] bytes) { source = ByteBuffer.wrap(bytes); }
        @Override public boolean isFinished() { return !source.hasRemaining(); }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { }
        @Override public int read() { throw new AssertionError("ByteBuffer input expected"); }
        @Override public int read(byte[] target, int offset, int length) {
            throw new AssertionError("Direct input must not fall back to byte[]");
        }
        @Override public int read(ByteBuffer target) {
            assertTrue(target.isDirect());
            readCalls++;
            int count = Math.min(source.remaining(), target.remaining());
            target.put(source.slice(source.position(), count));
            source.position(source.position() + count);
            return count;
        }
    }

    private static final class HoldingWriter implements DownstreamWriter {
        ByteBuffer pending;
        Completion completion;
        int writeCalls, flushCalls;

        @Override public void write(ByteBuffer buffer, Completion completion) {
            assertNull(this.completion, "Native writes must not overlap");
            pending = buffer;
            this.completion = completion;
            writeCalls++;
        }
        @Override public void flush(Completion completion) {
            assertNull(this.completion, "Native flush must wait for the body write");
            this.completion = completion;
            flushCalls++;
        }
        byte[] pendingBytes() {
            assertNotNull(pending);
            byte[] bytes = new byte[pending.remaining()];
            pending.duplicate().get(bytes);
            return bytes;
        }
        void complete(Throwable failure) {
            assertNotNull(completion, "No native write/flush is pending");
            Completion callback = completion;
            completion = null;
            pending = null;
            callback.complete(failure);
        }
    }

    private static final class HoldingOutput extends ServletOutputStream {
        private WriteListener listener;
        private byte[] pending;
        private int offset, length;
        int writeCalls, flushCalls;
        final ByteArrayOutputStream completed = new ByteArrayOutputStream();

        @Override public boolean isReady() { return pending == null; }
        @Override public void setWriteListener(WriteListener listener) { this.listener = listener; }
        @Override public void write(int value) { throw new AssertionError("Bulk output expected"); }
        @Override public void write(byte[] bytes, int offset, int length) {
            assertTrue(isReady(), "Write attempted under downstream pressure");
            pending = bytes;
            this.offset = offset;
            this.length = length;
            writeCalls++;
        }
        @Override public void flush() {
            assertTrue(isReady(), "Flush attempted under downstream pressure");
            flushCalls++;
        }
        byte[] pendingBytes() {
            assertNotNull(pending, "No pending servlet write");
            return Arrays.copyOfRange(pending, offset, offset + length);
        }
        void completeWrite() throws IOException {
            assertNotNull(pending);
            completed.write(pending, offset, length);
            pending = null;
            listener.onWritePossible();
        }
    }
}
