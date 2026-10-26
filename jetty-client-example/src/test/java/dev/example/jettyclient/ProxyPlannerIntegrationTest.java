package dev.example.jettyclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.ProxyCall;
import dev.example.jettyclient.proxy.ProxyExchangeResult;
import dev.example.jettyclient.proxy.ProxyHeaderCustomizer;
import dev.example.jettyclient.proxy.ProxyHeaderEditor;
import dev.example.jettyclient.proxy.ProxyRequestMetadata;
import dev.example.jettyclient.proxy.ProxyRequestPlan;
import dev.example.jettyclient.proxy.ProxyRequestPlanner;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies application planners and already-async legacy adapters over real Jetty HTTP. */
@Timeout(20)
@Execution(ExecutionMode.SAME_THREAD)
class ProxyPlannerIntegrationTest {
    private static final String CONTEXT = "/portal";
    private static final String CLIENT_ATTRIBUTE = ProxyPlannerIntegrationTest.class.getName() + ".client";
    private static final String BOUNDARY = "Planner-Integrity-Boundary";
    private static final String MULTIPART_TYPE = "multipart/form-data; boundary=" + BOUNDARY;
    private static final AtomicInteger UPSTREAM_REQUESTS = new AtomicInteger();
    private static final AtomicLong WRAPPED_BYTES = new AtomicLong();
    private static final AtomicReference<ProxyCall> SUPPLIED_CALL = new AtomicReference<>();
    private static final AtomicReference<Throwable> FIXTURE_FAILURE = new AtomicReference<>();
    private static HttpServer upstream;
    private static ExecutorService workers;
    private static ConfigurableApplicationContext application;
    private static JettyProxyEngine engine;
    private static HttpClient client;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(workers);
        upstream.createContext("/", ProxyPlannerIntegrationTest::serve);
        upstream.start();
        application = new SpringApplicationBuilder(JettyClientExampleApplication.class, ApplicationAdapter.class)
                .run("--server.port=0", "--server.servlet.context-path=" + CONTEXT,
                        "--spring.main.banner-mode=off", "--jetty.proxy.exchange-timeout=5s",
                        "--jetty.proxy.upstream=http://127.0.0.1:1/unused-default");
        Object server = application.getClass().getMethod("getWebServer").invoke(application);
        port = (int) server.getClass().getMethod("getPort").invoke(server);
        engine = application.getBean(JettyProxyEngine.class);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    @BeforeEach
    void resetObservations() {
        WRAPPED_BYTES.set(0);
        SUPPLIED_CALL.set(null);
    }

    @AfterEach
    void releaseAndCheckFixture() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, engine.activeRequests()));
        assertNull(FIXTURE_FAILURE.get(), "The independent HTTP fixture must complete correctly");
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
        if (client != null) client.close();
        if (upstream != null) upstream.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    @Test
    void applicationPlannerReplacesFallbackAndLeavesLocalRoutesToMvc() throws Exception {
        assertEquals(1, application.getBeanNamesForType(ProxyRequestPlanner.class).length);
        int before = UPSTREAM_REQUESTS.get();

        HttpResponse<String> local = client.send(request("/local").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, local.statusCode());
        assertEquals("local", local.body());
        HttpResponse<String> oldDemoRoute = client.send(request("/proxy/no-longer-selected").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, oldDemoRoute.statusCode(), "A custom planner replaces the fixed /proxy fallback");
        assertEquals(before, UPSTREAM_REQUESTS.get(), "Neither local route should reach upstream");
    }

    @Test
    void preservesRawTargetAndUsesPreparedAttributeAndHeaderSnapshots() throws Exception {
        String suffix = "/inspect/a%20b/%E2%82%AC?x=1&x=2&encoded=%2F&plus=a+b&empty=";
        HttpResponse<String> response = client.send(request("/gateway" + suffix)
                        .header("X-Test-Client", "browser-a").header("X-Test-Request", "request-1")
                        .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("GET\n/base" + suffix + "\nbrowser-a\nrequest-1", response.body());
        assertEquals("browser-a", response.headers().firstValue("X-Response-Client").orElseThrow());
    }

    @Test
    void applicationPlannedMultipartPostAndPutKeepEveryWireByte() throws Exception {
        byte[] body = multipartBody();
        for (String method : List.of("POST", "PUT")) {
            String preparedClient = "client-" + method;
            HttpResponse<byte[]> response = client.send(request("/gateway/echo")
                            .header("Content-Type", MULTIPART_TYPE).header("X-Test-Client", preparedClient)
                            .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertEquals(method, response.headers().firstValue("X-Received-Method").orElseThrow());
            assertEquals(MULTIPART_TYPE, response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(preparedClient, response.headers().firstValue("X-Received-Client").orElseThrow());
            assertArrayEquals(body, response.body(), "Multipart boundaries, Unicode and binary bytes must survive");
            await().atMost(Duration.ofSeconds(3)).until(() -> engine.activeRequests() == 0);
        }
    }

    @Test
    void acceptsExistingAsyncContextWithActualRequestAndResponseWrappers() throws Exception {
        byte[] body = new byte[192 * 1024 + 13];
        new Random(74).nextBytes(body);
        HttpResponse<byte[]> response = client.send(request("/supplied/echo")
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertArrayEquals(body, response.body());
        assertEquals("request-wrapper", response.headers().firstValue("X-Received-Wrapper").orElseThrow());
        assertEquals(body.length, WRAPPED_BYTES.get(), "The actual output wrapper must observe all body bytes");
        await().atMost(Duration.ofSeconds(2)).until(() -> SUPPLIED_CALL.get() != null);
        ProxyExchangeResult result = SUPPLIED_CALL.get().completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertTrue(result.success(), () -> String.valueOf(result.failure()));
        assertEquals(body.length, result.uploadedBytes());
        assertEquals(body.length, result.downloadedBytes());
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + CONTEXT + path))
                .timeout(Duration.ofSeconds(8));
    }

    private static URI target(String suffix, String query) {
        return URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/base" + suffix
                + (query == null ? "" : "?" + query));
    }

    private static byte[] multipartBody() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\n"
                + "Проверка границ и байтов\r\n--" + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"payload.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] binary = new byte[256 * 1024 + 29];
        new Random(18).nextBytes(binary);
        body.write(binary);
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    private static void serve(HttpExchange exchange) throws IOException {
        UPSTREAM_REQUESTS.incrementAndGet();
        try (exchange) {
            byte[] incoming = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getRawPath();
            byte[] body = incoming;
            if (path.startsWith("/base/inspect/")) {
                body = (exchange.getRequestMethod() + "\n" + exchange.getRequestURI().toASCIIString()
                        + "\n" + exchange.getRequestHeaders().getFirst("X-Client-Snapshot")
                        + "\n" + exchange.getRequestHeaders().getFirst("X-Request-Snapshot"))
                        .getBytes(StandardCharsets.UTF_8);
            }
            for (String[] pair : List.of(new String[]{"Content-Type", "Content-Type"},
                    new String[]{"X-Client-Snapshot", "X-Received-Client"},
                    new String[]{"X-Wrapper-Signal", "X-Received-Wrapper"})) {
                String value = exchange.getRequestHeaders().getFirst(pair[0]);
                if (value != null) exchange.getResponseHeaders().add(pair[1], value);
            }
            exchange.getResponseHeaders().add("X-Received-Method", exchange.getRequestMethod());
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Throwable failure) {
            FIXTURE_FAILURE.compareAndSet(null, failure);
            if (failure instanceof IOException io) throw io;
            throw new IOException(failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ApplicationAdapter {
        @Bean
        ProxyRequestPlanner applicationPlanner() {
            return request -> {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (!(path.equals("/gateway") || path.startsWith("/gateway/"))) return null;
                String clientId = (String) request.getAttribute(CLIENT_ATTRIBUTE);
                String requestId = request.getHeader("X-Test-Request");
                String clientSnapshot = clientId == null ? "anonymous" : clientId;
                String requestSnapshot = requestId == null ? "unassigned" : requestId;
                String suffix = path.length() == "/gateway".length() ? "/" : path.substring("/gateway".length());
                return ProxyRequestPlan.builder(target(suffix, request.getQueryString()))
                        .customizer(new ProxyHeaderCustomizer() {
                            @Override public void customizeRequest(ProxyRequestMetadata metadata, ProxyHeaderEditor headers) {
                                headers.set("X-Client-Snapshot", clientSnapshot);
                                headers.set("X-Request-Snapshot", requestSnapshot);
                            }
                            @Override public void customizeResponse(ProxyRequestMetadata metadata, int status,
                                    ProxyHeaderEditor headers) {
                                headers.set("X-Response-Client", clientSnapshot);
                            }
                        }).build();
            };
        }

        @Bean
        FilterRegistrationBean<Filter> applicationPreparation(JettyProxyEngine engine) {
            Filter adapter = (request, response, chain) -> {
                HttpServletRequest incoming = (HttpServletRequest) request;
                incoming.setAttribute(CLIENT_ATTRIBUTE, incoming.getHeader("X-Test-Client"));
                String path = incoming.getRequestURI().substring(incoming.getContextPath().length());
                if (!path.equals("/supplied/echo")) {
                    chain.doFilter(request, response);
                    return;
                }
                HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(incoming) {
                    @Override public String getHeader(String name) {
                        return "X-Wrapper-Signal".equalsIgnoreCase(name) ? "request-wrapper" : super.getHeader(name);
                    }
                    @Override public Enumeration<String> getHeaders(String name) {
                        return "X-Wrapper-Signal".equalsIgnoreCase(name)
                                ? Collections.enumeration(List.of("request-wrapper")) : super.getHeaders(name);
                    }
                    @Override public Enumeration<String> getHeaderNames() {
                        var names = Collections.list(super.getHeaderNames());
                        names.add("X-Wrapper-Signal");
                        return Collections.enumeration(names);
                    }
                };
                HttpServletResponse wrappedResponse = new HttpServletResponseWrapper((HttpServletResponse) response) {
                    private ServletOutputStream output;
                    @Override public ServletOutputStream getOutputStream() throws IOException {
                        if (output == null) {
                            ServletOutputStream delegate = super.getOutputStream();
                            output = new ServletOutputStream() {
                                @Override public boolean isReady() { return delegate.isReady(); }
                                @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                                @Override public void write(int value) throws IOException {
                                    delegate.write(value);
                                    WRAPPED_BYTES.incrementAndGet();
                                }
                                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                                    delegate.write(bytes, offset, length);
                                    WRAPPED_BYTES.addAndGet(length);
                                }
                                @Override public void flush() throws IOException { delegate.flush(); }
                                @Override public void close() throws IOException { delegate.close(); }
                            };
                        }
                        return output;
                    }
                };
                AsyncContext async = wrappedRequest.startAsync(wrappedRequest, wrappedResponse);
                SUPPLIED_CALL.set(engine.forward(wrappedRequest, wrappedResponse, async,
                        ProxyRequestPlan.builder(target("/echo", null)).build()));
            };
            var registration = new FilterRegistrationBean<>(adapter);
            registration.setName("plannerApplicationPreparation");
            registration.addUrlPatterns("/*");
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setAsyncSupported(true);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
