package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A caller-created timeout-zero async must never be orphaned by Servlet accessor failures. */
@Timeout(10)
class JettyProxyPreflightFailureTest {
    private static final URI TARGET = URI.create("http://upstream.example/resource");

    enum Probe { METHOD, EXPECT, TRANSFER_ENCODING, ASYNC_SUPPORTED, HEADER_SNAPSHOT, CONTENT_LENGTH }

    @ParameterizedTest
    @EnumSource(Probe.class)
    void suppliedAsyncIsCompletedAndReturnsFailureWhenPreflightThrows(Probe probe) {
        try (Fixture fixture = new Fixture()) {
            fixture.prestarted();
            IllegalStateException failure = fixture.fail(probe);

            ProxyCall call = assertDoesNotThrow(() -> fixture.engine.forward(fixture.incoming,
                    fixture.outgoing, fixture.async, ProxyRequestPlan.builder(TARGET).build()));

            fixture.assertRejected(call, failure);
            verify(fixture.async, times(1)).complete();
            verify(fixture.incoming, never()).startAsync(any(), any());
        }
    }

    @ParameterizedTest
    @EnumSource(Probe.class)
    void freshRequestReturnsFailureBeforeStartingAsyncWhenPreflightThrows(Probe probe) {
        try (Fixture fixture = new Fixture()) {
            IllegalStateException failure = fixture.fail(probe);

            ProxyCall call = assertDoesNotThrow(() -> fixture.engine.forward(fixture.incoming,
                    fixture.outgoing, ProxyRequestPlan.builder(TARGET).build()));

            fixture.assertRejected(call, failure);
            verify(fixture.async, never()).complete();
            verify(fixture.incoming, never()).startAsync(any(), any());
        }
    }

    @Test
    void failureToStartAsyncReleasesAdmissionAndReturnsLocalFailure() {
        try (Fixture fixture = new Fixture()) {
            IllegalStateException failure = new IllegalStateException("Async cannot start");
            when(fixture.incoming.startAsync(fixture.incoming, fixture.outgoing)).thenThrow(failure);

            ProxyCall call = assertDoesNotThrow(() -> fixture.engine.forward(fixture.incoming,
                    fixture.outgoing, ProxyRequestPlan.builder(TARGET).build()));

            fixture.assertRejected(call, failure);
            verify(fixture.incoming, times(1)).startAsync(fixture.incoming, fixture.outgoing);
            verify(fixture.async, never()).complete();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpClient client = mock(HttpClient.class);
        final HttpServletRequest incoming = mock(HttpServletRequest.class);
        final HttpServletResponse outgoing = mock(HttpServletResponse.class);
        final AsyncContext async = mock(AsyncContext.class);
        final JettyProxyEngine engine;

        Fixture() {
            when(client.getExecutor()).thenReturn((Executor)Runnable::run);
            when(incoming.getMethod()).thenReturn("GET");
            when(incoming.isAsyncSupported()).thenReturn(true);
            when(incoming.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
            when(incoming.getHeaders(anyString())).thenAnswer(ignored -> Collections.emptyEnumeration());
            when(incoming.getContentLengthLong()).thenReturn(-1L);
            when(incoming.startAsync(incoming, outgoing)).thenReturn(async);
            when(async.getRequest()).thenReturn(incoming);
            when(async.getResponse()).thenReturn(outgoing);
            JettyProxyProperties properties = new JettyProxyProperties(URI.create("http://127.0.0.1:9000"),
                    1024, 16_384, 1, 1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));
            engine = new JettyProxyEngine(client, properties);
        }

        void prestarted() { when(incoming.isAsyncStarted()).thenReturn(true); }

        IllegalStateException fail(Probe probe) {
            IllegalStateException failure = new IllegalStateException("Servlet probe failed: " + probe);
            switch (probe) {
                case METHOD -> when(incoming.getMethod()).thenThrow(failure);
                case EXPECT -> when(incoming.getHeader("Expect")).thenThrow(failure);
                case TRANSFER_ENCODING -> when(incoming.getHeaders("Transfer-Encoding")).thenThrow(failure);
                case ASYNC_SUPPORTED -> when(incoming.isAsyncSupported()).thenThrow(failure);
                case HEADER_SNAPSHOT -> when(incoming.getHeaderNames()).thenThrow(failure);
                case CONTENT_LENGTH -> when(incoming.getContentLengthLong()).thenThrow(failure);
            }
            return failure;
        }

        void assertRejected(ProxyCall call, Throwable failure) {
            ProxyExchangeResult result = call.completion().toCompletableFuture().join();
            assertEquals(500, result.status());
            assertSame(failure, result.failure());
            assertFalse(result.success());
            assertEquals(0, engine.activeRequests());
            verify(outgoing).setStatus(500);
            verify(outgoing).setContentLength(0);
            verify(outgoing).setHeader("Connection", "close");
            verify(client, never()).newRequest(any(URI.class));
            verify(client, never()).getByteBufferPool();
            assertAdmissionRecovered();
        }

        private void assertAdmissionRecovered() {
            HttpServletRequest next = mock(HttpServletRequest.class);
            HttpServletResponse nextResponse = mock(HttpServletResponse.class);
            AsyncContext nextAsync = mock(AsyncContext.class);
            when(next.getMethod()).thenReturn("OPTIONS");
            when(next.isAsyncSupported()).thenReturn(true);
            when(next.getHeaderNames()).thenReturn(Collections.enumeration(List.of("Max-Forwards")));
            when(next.getHeaders(anyString())).thenAnswer(invocation -> Collections.enumeration(
                    "Max-Forwards".equals(invocation.getArgument(0)) ? List.of("0") : List.of()));
            when(next.getContentLengthLong()).thenReturn(-1L);
            when(next.startAsync(next, nextResponse)).thenReturn(nextAsync);
            when(client.getExecutor()).thenReturn((Executor)Runnable::run);

            ProxyCall recovered = engine.forward(next, nextResponse, ProxyRequestPlan.builder(TARGET).build());
            assertEquals(200, recovered.completion().toCompletableFuture().join().status(),
                    "Preflight failure must return the single admission permit");
            assertEquals(0, engine.activeRequests());
            verify(nextAsync).complete();
            verify(client, never()).newRequest(any(URI.class));
        }

        @Override public void close() { engine.close(); }
    }
}
