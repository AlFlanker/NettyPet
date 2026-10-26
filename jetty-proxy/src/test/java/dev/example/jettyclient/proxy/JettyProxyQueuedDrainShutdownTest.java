package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** An accepted executor job can still be discarded when the actual Jetty pool stops. */
@Timeout(10)
class JettyProxyQueuedDrainShutdownTest {
    @Test
    void acceptedButUnexecutedExchangeDrainStillCleansUpWhenJettyDiscardsItsQueue() throws Exception {
        QueuedThreadPool executor = new QueuedThreadPool(1, 1, 60_000);
        executor.setName("queued-drain-shutdown-test");
        executor.setReservedThreads(0);
        // This deliberately exercises the immediate-stop path which rejects queued jobs.
        executor.setStopTimeout(0);
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerExited = new CountDownLatch(1);

        // No network client is started: its executor is all this pre-start exchange needs.
        HttpClient client = new ProxyHttpClient(new org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP(1));
        client.setExecutor(executor);
        JettyProxyProperties properties = new JettyProxyProperties(URI.create("http://127.0.0.1:9000"),
                1024, 16_384, 1, 1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));
        JettyProxyServlet servlet = new JettyProxyServlet(client, properties);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        AsyncContext async = mock(AsyncContext.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.isAsyncSupported()).thenReturn(true);
        when(request.getRequestURI()).thenReturn("/proxy/stalled-before-start");
        when(request.getContextPath()).thenReturn("");
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getHeaders(anyString())).thenAnswer(ignored -> Collections.emptyEnumeration());
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.startAsync(request, response)).thenReturn(async);

        try {
            executor.start();
            executor.execute(() -> {
                workerEntered.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    workerExited.countDown();
                }
            });
            assertTrue(workerEntered.await(2, TimeUnit.SECONDS));

            servlet.service(request, response);
            assertEquals(1, servlet.activeRequests());
            assertEquals(1, executor.getQueueSize(), "The exchange drain must be accepted but not yet run");
            verify(async, never()).complete();

            servlet.destroy();
            // The existing drain owns its WIP token; shutdown must not need another client-pool job.
            assertEquals(1, executor.getQueueSize());
            executor.stop();

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertEquals(0, servlet.activeRequests(),
                        "Discarding an already accepted drain must not strand the exchange/admission");
                verify(async, times(1)).complete();
            });
            verify(response).setStatus(503);
            verify(response).setContentLength(0);
            verify(response).setHeader("Connection", "close");
        } finally {
            servlet.destroy();
            releaseWorker.countDown();
            executor.stop();
            assertTrue(workerExited.await(2, TimeUnit.SECONDS), "The fixture must not leave a worker running");
        }
    }
}
