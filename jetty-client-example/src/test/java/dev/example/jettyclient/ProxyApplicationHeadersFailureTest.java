package dev.example.jettyclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.ProxyRequestPlan;
import dev.example.jettyclient.proxy.ProxyRequestPlanner;
import dev.example.jettyclient.proxy.ProxyResponseBodyPolicy;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

/** Local proxy errors retain application headers without resurrecting upstream representation metadata. */
@Timeout(20)
@Execution(ExecutionMode.SAME_THREAD)
class ProxyApplicationHeadersFailureTest {
    private static final String ORIGIN = "https://frontend.example";
    private static final String POLICY = "default-src 'self'";
    private static final CountDownLatch RELEASE_STALL = new CountDownLatch(1);
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
        upstream.createContext("/", ProxyApplicationHeadersFailureTest::serve);
        upstream.start();
        application = new SpringApplicationBuilder(JettyClientExampleApplication.class, ApplicationHeaders.class)
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--jetty.proxy.exchange-timeout=500ms", "--jetty.proxy.idle-timeout=5s");
        Object server = application.getClass().getMethod("getWebServer").invoke(application);
        port = (int)server.getClass().getMethod("getPort").invoke(server);
        engine = application.getBean(JettyProxyEngine.class);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void releasesAdmission() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, engine.activeRequests()));
    }

    @AfterAll
    static void stop() throws Exception {
        RELEASE_STALL.countDown();
        if (client != null) client.close();
        if (application != null) application.close();
        if (upstream != null) upstream.stop(0);
        if (workers != null) {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void keepsApplicationCookieCorsAndSecurityHeadersOnBadGateway() throws Exception {
        assertApplicationHeadersOnError("close", 502);
    }

    @Test
    void keepsApplicationCookieCorsAndSecurityHeadersOnDeadline() throws Exception {
        try {
            assertApplicationHeadersOnError("stall", 504);
        } finally {
            RELEASE_STALL.countDown();
        }
    }

    @Test
    void localErrorRestoresOnlyApplicationHeadersAfterAnIncompleteBufferedResponse() throws Exception {
        HttpResponse<byte[]> response = assertApplicationHeadersOnError("incomplete-rewrite", 502);

        assertTrue(response.headers().allValues("X-Upstream-Only").isEmpty());
        assertTrue(response.headers().allValues("Content-Type").isEmpty());
        assertTrue(response.headers().allValues("ETag").isEmpty());
        assertFalse(response.headers().allValues("Set-Cookie").stream()
                .anyMatch(value -> value.startsWith("backend-only=")));
        assertEquals(0, response.body().length, "No incomplete upstream content may become the local error body");
    }

    @Test
    void successfulUpstreamVaryPreservesTheApplicationCorsCacheDimensions() throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/proxy/vary"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertArrayEquals("ok".getBytes(StandardCharsets.US_ASCII), response.body());
        assertEquals(Set.of("origin", "access-control-request-headers", "accept-encoding"), varyTokens(response),
                "Upstream Vary must add its cache dimensions to those set by the application CORS Filter");
    }

    private static HttpResponse<byte[]> assertApplicationHeadersOnError(String route, int expectedStatus)
            throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/proxy/" + route))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(expectedStatus, response.statusCode());
        assertEquals(ORIGIN, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        assertEquals(POLICY, response.headers().firstValue("Content-Security-Policy").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertEquals(1, cookies.size(), "Only the cookie issued by the application should remain");
        assertTrue(cookies.getFirst().startsWith("application-session=portal;"), cookies.toString());
        assertEquals(Set.of("origin", "access-control-request-headers"), varyTokens(response));
        for (String stale : List.of("Content-Type", "Content-Encoding", "Content-Range", "ETag", "Content-MD5",
                "Digest", "Content-Digest", "Repr-Digest", "Last-Modified", "Accept-Ranges",
                "Transfer-Encoding", "Trailer"))
            assertTrue(response.headers().allValues(stale).isEmpty(),
                    "Local errors must clear stale application/upstream representation metadata: " + stale);
        assertEquals(List.of("0"), response.headers().allValues("Content-Length"));
        assertEquals(List.of("no-store"), response.headers().allValues("Cache-Control"),
                "A local failure must not inherit the successful application's public cache policy");
        return response;
    }

    private static Set<String> varyTokens(HttpResponse<?> response) {
        return response.headers().allValues("Vary").stream()
                .flatMap(value -> Stream.of(value.split(",")))
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private static void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            switch (exchange.getRequestURI().getPath()) {
                case "/close" -> { /* Close before any upstream response headers. */ }
                case "/stall" -> {
                    try { RELEASE_STALL.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                case "/incomplete-rewrite" -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.getResponseHeaders().set("X-Upstream-Only", "must-not-survive-local-error");
                    exchange.getResponseHeaders().set("ETag", "\"incomplete\"");
                    exchange.getResponseHeaders().set("Set-Cookie", "backend-only=discard; Path=/");
                    exchange.sendResponseHeaders(200, 10);
                    exchange.getResponseBody().write("abc".getBytes(StandardCharsets.US_ASCII));
                    // Deliberate invalid Content-Length; closing the response below truncates its body.
                }
                case "/vary" -> {
                    exchange.getResponseHeaders().set("Vary", "Accept-Encoding");
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write("ok".getBytes(StandardCharsets.US_ASCII));
                }
                default -> throw new IOException("Unexpected upstream route " + exchange.getRequestURI());
            }
        } catch (IOException failure) {
            if (!exchange.getRequestURI().getPath().equals("/incomplete-rewrite")) throw failure;
            // HttpServer reports the intentionally incomplete fixed-length response on close.
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ApplicationHeaders {
        @Bean
        FilterRegistrationBean<Filter> applicationHeadersFilter() {
            Filter filter = (request, response, chain) -> {
                HttpServletResponse outgoing = (HttpServletResponse)response;
                Cookie cookie = new Cookie("application-session", "portal");
                cookie.setPath("/");
                cookie.setHttpOnly(true);
                outgoing.addCookie(cookie);
                outgoing.setHeader("Access-Control-Allow-Origin", ORIGIN);
                outgoing.setHeader("Access-Control-Allow-Credentials", "true");
                outgoing.setHeader("Vary", "Origin");
                outgoing.addHeader("Vary", "Access-Control-Request-Headers");
                outgoing.setHeader("Content-Security-Policy", POLICY);
                outgoing.setHeader("X-Content-Type-Options", "nosniff");
                if (!((HttpServletRequest)request).getRequestURI().endsWith("/vary")) {
                    outgoing.setContentType("application/octet-stream");
                    outgoing.setContentLengthLong(1000);
                    outgoing.setHeader("Content-Encoding", "gzip");
                    outgoing.setHeader("Content-Range", "bytes 0-9/10");
                    outgoing.setHeader("ETag", "\"stale-application-entity\"");
                    outgoing.setHeader("Content-MD5", "AAAAAAAAAAAAAAAAAAAAAA==");
                    outgoing.setHeader("Digest", "sha-256=stale");
                    outgoing.setHeader("Content-Digest", "sha-256=:c3RhbGU=:");
                    outgoing.setHeader("Repr-Digest", "sha-256=:c3RhbGU=:");
                    outgoing.setHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT");
                    outgoing.setHeader("Accept-Ranges", "bytes");
                    outgoing.setHeader("Transfer-Encoding", "chunked");
                    outgoing.setHeader("Trailer", "Digest");
                    outgoing.setHeader("Cache-Control", "public, max-age=3600");
                }
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setName("applicationHeadersFailureFilter");
            registration.setUrlPatterns(List.of("/proxy", "/proxy/*"));
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setAsyncSupported(true);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }

        @Bean
        ProxyRequestPlanner planner() {
            return request -> {
                String path = request.getRequestURI();
                if (!path.startsWith("/proxy/")) return null;
                var plan = ProxyRequestPlan.builder(URI.create("http://127.0.0.1:"
                        + upstream.getAddress().getPort() + path.substring("/proxy".length())));
                if (path.endsWith("incomplete-rewrite"))
                    plan.responseBody((incoming, response) -> new ProxyResponseBodyPolicy.Selection(
                            1024, 1024, (metadata, body) -> body));
                return plan.build();
            };
        }
    }
}
