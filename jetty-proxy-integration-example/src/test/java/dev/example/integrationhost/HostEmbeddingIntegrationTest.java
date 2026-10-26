package dev.example.integrationhost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.ProxyErrorResponse;
import dev.example.jettyclient.proxy.ProxyRequestPlan;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Both production handlers are hosted behind one application Filter, with no demo dependency. */
@Timeout(25)
class HostEmbeddingIntegrationTest {
    enum Transport { JETTY, NETTY }
    private static final byte[] LARGE = randomBytes(8 * 1024 * 1024, 803);
    private static final String BOUNDARY = "Host-Integration-Boundary-129";
    private static final String MULTIPART = "multipart/form-data; boundary=" + BOUNDARY;
    private static final AtomicInteger UPSTREAM_REQUESTS = new AtomicInteger();
    private static final AtomicInteger WRAPPED_BYTES = new AtomicInteger();
    private static final AtomicReference<Throwable> UPSTREAM_FAILURE = new AtomicReference<>();
    private static final List<Host> HOSTS = new ArrayList<>();
    private static HttpServer upstream;
    private static ExecutorService workers;
    private static HttpClient client;

    @BeforeAll
    static void start() throws Exception {
        workers = Executors.newVirtualThreadPerTaskExecutor();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(workers);
        upstream.createContext("/", HostEmbeddingIntegrationTest::respond);
        upstream.start();
        HOSTS.add(startHost(Transport.JETTY));
        HOSTS.add(startHost(Transport.NETTY));
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterEach
    void released() {
        for (Host host : HOSTS) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertEquals(0, host.context().getBean(JettyProxyEngine.class).activeRequests());
                if (host.transport() == Transport.NETTY)
                    assertEquals(0, host.context().getBean(dev.example.proxy.transport.ProxyEngine.class).activeRequests());
            });
        }
        assertNull(UPSTREAM_FAILURE.get());
    }

    @AfterAll
    static void stop() {
        if (client != null) client.close();
        for (Host host : HOSTS.reversed()) host.context().close();
        if (upstream != null) upstream.stop(0);
        if (workers != null) workers.close();
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void localRoutesAndDemoPathsDoNotReachTheUpstream(Transport transport) throws Exception {
        int before = UPSTREAM_REQUESTS.get();
        assertEquals("host-local", text(transport, "/local").body());
        assertEquals(404, text(transport, "/proxy/demo-must-not-exist").statusCode());
        assertEquals(before, UPSTREAM_REQUESTS.get());
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void forwardsRawPathQueryAndCustomMethodThroughTheSelectedHandler(Transport transport) throws Exception {
        String suffix = "/inspect/a%20b/%E2%82%AC?x=1&x=2&encoded=%2F&plus=a+b&empty=";
        int before = UPSTREAM_REQUESTS.get();
        HttpResponse<String> response = client.send(request(transport, "/backend" + suffix)
                .method("FROB", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("FROB|/base" + suffix, response.body());
        assertEquals(before + 1, UPSTREAM_REQUESTS.get(), "Exactly one selected pipeline may send upstream");
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void transfersEightMebibytesWithoutCorruption(Transport transport) throws Exception {
        HttpResponse<byte[]> response = client.send(request(transport, "/backend/large").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals(LARGE.length, response.body().length);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(LARGE),
                MessageDigest.getInstance("SHA-256").digest(response.body()));
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void multipartPostPutAndUrlencodedPutPreserveAllWireBytes(Transport transport) throws Exception {
        byte[] body = multipart();
        for (String method : List.of("POST", "PUT")) {
            HttpResponse<byte[]> response = client.send(request(transport, "/backend/echo")
                    .header("Content-Type", MULTIPART)
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals(method, response.headers().firstValue("X-Received-Method").orElseThrow());
            assertEquals(MULTIPART, response.headers().firstValue("X-Received-Type").orElseThrow());
            assertArrayEquals(body, response.body(), "Multipart must not be parsed or reconstructed");
        }
        byte[] form = "x=%2F&x=second+value&literal=%252F&empty=".getBytes(StandardCharsets.US_ASCII);
        HttpResponse<byte[]> response = client.send(request(transport, "/backend/echo")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(form)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(form, response.body(), "The host Filter must run before the MVC form parser");
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void requestAndResponseWrappersRemainInTheSelectedPipeline(Transport transport) throws Exception {
        WRAPPED_BYTES.set(0);
        byte[] body = randomBytes(192 * 1024 + 17, 122);
        HttpResponse<byte[]> response = client.send(request(transport, "/backend/wrapped-echo")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("host-wrapper", response.headers().firstValue("X-Received-Wrapper").orElseThrow());
        assertArrayEquals(body, response.body());
        assertEquals(body.length, WRAPPED_BYTES.get());
    }

    @Test
    void newPipelineKeepsApplicationCookiesSeparateFromBackendCookies() throws Exception {
        HttpResponse<String> first = text(Transport.JETTY, "/backend/cookies");
        assertEquals(200, first.statusCode());
        assertEquals(List.of("application=host; Path=/", "first=1; Path=/", "second=2; Path=/"),
                first.headers().allValues("Set-Cookie"));
        HttpResponse<String> next = text(Transport.JETTY, "/backend/cookie-inspect");
        assertEquals("none", next.body(), "Neither library client may retain another user's cookies");
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void forbiddenRouteIsRejectedBeforeEitherTransport(Transport transport) throws Exception {
        int before = UPSTREAM_REQUESTS.get();
        assertEquals(403, text(transport, "/backend/forbidden/resource").statusCode());
        assertEquals(before, UPSTREAM_REQUESTS.get());
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void percentEncodedForbiddenRouteCannotBypassTheCommonApplicationGuard(Transport transport) throws Exception {
        int before = UPSTREAM_REQUESTS.get();
        assertEquals(403, text(transport, "/backend/%66orbidden/resource").statusCode());
        assertEquals(before, UPSTREAM_REQUESTS.get());
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void synchronousResourceLoadUsesItsPathAndGetRegardlessOfOriginalPost(Transport transport) throws Exception {
        String path = "/include/a%20b?x=1&x=2&encoded=%2F";
        String encoded = URLEncoder.encode(path, StandardCharsets.UTF_8);
        HttpResponse<String> response = client.send(request(transport, "/resource?path=" + encoded)
                .POST(HttpRequest.BodyPublishers.ofString("original-body-must-not-be-forwarded")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("GET|/base" + path, response.body());
    }

    @Test
    void synchronousLoaderDecodesBoundedCompressedTextWithDeclaredCharsetAndSeparateCookies() throws Exception {
        HttpResponse<String> response = text(Transport.JETTY,
                "/resource?path=" + URLEncoder.encode("/include-gzip", StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());
        assertEquals("Привет из include", response.body());
        assertEquals(List.of("include-first=1; Path=/", "include-second=2; Path=/"),
                response.headers().allValues("Set-Cookie"));
        assertTrue(response.headers().allValues("Content-Encoding").isEmpty(),
                "The MVC text response is re-encoded and must not retain origin compression metadata");
    }

    @Test
    void synchronousLoaderRejectsAnUntrustedAbsoluteUrlBeforeSendingUpstream() throws Exception {
        int before = UPSTREAM_REQUESTS.get();
        HttpResponse<String> response = text(Transport.JETTY,
                "/resource?path=" + URLEncoder.encode("http://example.invalid/resource", StandardCharsets.UTF_8));
        assertEquals(400, response.statusCode());
        assertEquals(before, UPSTREAM_REQUESTS.get());
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void resourceIncludeUsesTheSameForbiddenPolicyAsTheStreamingRoute(Transport transport) throws Exception {
        int before = UPSTREAM_REQUESTS.get();
        for (String path : List.of("/forbidden/private", "/%66orbidden/private")) {
            assertEquals(403, text(transport, "/resource?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8))
                    .statusCode());
        }
        assertEquals(before, UPSTREAM_REQUESTS.get());
    }

    @Test
    void maintenanceBlocksBothStreamingAndResourceIncludesBeforeTheOrigin() throws Exception {
        Host maintenance = startHost(Transport.JETTY, "--host.proxy.maintenance=true");
        try {
            int before = UPSTREAM_REQUESTS.get();
            for (String path : List.of("/backend/include", "/resource?path=%2Finclude")) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + maintenance.port() + "/host" + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(503, response.statusCode());
            }
            assertEquals(before, UPSTREAM_REQUESTS.get());
        } finally { maintenance.context().close(); }
    }

    @Test
    void fullResourceLoadDoesNotForwardOuterRangeOrConditionalHeaders() throws Exception {
        HttpResponse<String> response = client.send(request(Transport.JETTY, "/resource?path=%2Finclude-headers")
                .header("Range", "bytes=0-3").header("If-Range", "\"old\"")
                .header("If-None-Match", "\"outer\"").header("If-Modified-Since", "Wed, 21 Oct 2015 07:28:00 GMT")
                .header("If-Match", "\"outer\"").header("If-Unmodified-Since", "Wed, 21 Oct 2015 07:28:00 GMT")
                .header("Content-Type", "application/outer-format").header("Accept-Language", "ru")
                .header("X-Unapproved", "outer-only").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("full-resource|ru|gzip, deflate", response.body());
        assertTrue(response.headers().allValues("Content-Range").isEmpty());
        assertTrue(response.headers().allValues("ETag").isEmpty());
    }

    @Test
    void resourcePathCannotBeProvidedByThePostBodyOrAmbiguousQueryParameters() throws Exception {
        int before = UPSTREAM_REQUESTS.get();
        HttpResponse<String> bodyOnly = client.send(request(Transport.JETTY, "/resource")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("path=%2Finclude")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bodyOnly.statusCode());
        assertEquals(400, text(Transport.JETTY, "/resource?path=%2Finclude&path=%2Fforbidden").statusCode());
        assertEquals(before, UPSTREAM_REQUESTS.get());
    }

    @Test
    void resourceLoaderMapsPartialAuthenticationMethodAndRedirectResponsesToItsOwnBadGateway() throws Exception {
        for (int status : List.of(206, 401, 405, 302)) {
            int before = UPSTREAM_REQUESTS.get();
            HttpResponse<String> response = text(Transport.JETTY, "/resource?path=%2Finclude-status-" + status);
            assertEquals(502, response.statusCode(), "An include requires a complete 200; upstream status=" + status);
            for (String header : List.of("Content-Range", "WWW-Authenticate", "Allow", "Location", "Set-Cookie"))
                assertTrue(response.headers().allValues(header).isEmpty(), header);
            assertEquals(before + 1, UPSTREAM_REQUESTS.get(), "Do not follow redirects or retry the load");
        }
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void dynamicRouteDecisionsAreHonoredOrExplicitlyRejectedBeforeTheLegacyOrigin(Transport transport) throws Exception {
        for (String path : List.of("/dynamic-target", "/dynamic-timeout")) {
            int before = UPSTREAM_REQUESTS.get();
            HttpResponse<String> response = text(transport, "/backend" + path);
            if (transport == Transport.NETTY) {
                assertEquals(501, response.statusCode());
                assertEquals("fixed-backend-and-timeout-only",
                        response.headers().firstValue("X-Proxy-Capability").orElseThrow());
                assertEquals(before, UPSTREAM_REQUESTS.get());
            } else {
                assertEquals(200, response.statusCode());
                assertEquals("GET|/base/" + (path.equals("/dynamic-target") ? "selected-target" : "dynamic-timeout"),
                        response.body());
                assertEquals(before + 1, UPSTREAM_REQUESTS.get());
            }
        }
    }

    @Test
    void nativeJettyErrorPolicyWritesUtf8AndPreservesTheApplicationCookieBeforeCommit() throws Exception {
        int before = UPSTREAM_REQUESTS.get();
        String message = "Подсистема недоступна";
        HttpResponse<byte[]> response = client.send(request(Transport.JETTY, "/backend/fail-text").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        byte[] expected = message.getBytes(StandardCharsets.UTF_8);
        assertEquals(500, response.statusCode());
        assertArrayEquals(expected, response.body());
        assertEquals(Integer.toString(expected.length), response.headers().firstValue("Content-Length").orElseThrow());
        var contentType = org.springframework.http.MediaType.parseMediaType(
                response.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(org.springframework.http.MediaType.TEXT_PLAIN.isCompatibleWith(contentType));
        assertEquals(StandardCharsets.UTF_8, contentType.getCharset());
        assertEquals(List.of("application=host; Path=/"), response.headers().allValues("Set-Cookie"));
        assertEquals(before + 1, UPSTREAM_REQUESTS.get(), "An error renderer must not resend through another pipeline");
    }

    private static Host startHost(Transport transport, String... extraProperties) throws Exception {
        Class<?>[] sources = transport == Transport.JETTY
                ? new Class<?>[]{IntegrationHostApplication.class, WrapperConfiguration.class, ErrorPolicyConfiguration.class}
                : new Class<?>[]{IntegrationHostApplication.class, WrapperConfiguration.class, LegacyHandlerConfiguration.class,
                        LegacySelectionConfiguration.class};
        var arguments = new ArrayList<>(List.of("--server.port=0", "--server.servlet.context-path=/host",
                "--spring.main.banner-mode=off", "--host.proxy.upstream=http://127.0.0.1:"
                        + upstream.getAddress().getPort() + "/base", "--host.proxy.timeout=8s",
                "--jetty.proxy.exchange-timeout=8s"));
        arguments.addAll(List.of(extraProperties));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(sources)
                .run(arguments.toArray(String[]::new));
        Object server = context.getClass().getMethod("getWebServer").invoke(context);
        int port = (int)server.getClass().getMethod("getPort").invoke(server);
        return new Host(transport, context, port);
    }

    private static HttpRequest.Builder request(Transport transport, String path) {
        Host host = HOSTS.stream().filter(candidate -> candidate.transport() == transport).findFirst().orElseThrow();
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + "/host" + path))
                .timeout(Duration.ofSeconds(12));
    }

    private static HttpResponse<String> text(Transport transport, String path) throws Exception {
        return client.send(request(transport, path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void respond(HttpExchange exchange) throws IOException {
        UPSTREAM_REQUESTS.incrementAndGet();
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            if (path.equals("/base/fail-text")) return;
            byte[] body;
            if (path.equals("/base/large")) body = LARGE;
            else if (path.equals("/base/echo") || path.equals("/base/wrapped-echo")) {
                body = exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("X-Received-Method", exchange.getRequestMethod());
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                if (type != null) exchange.getResponseHeaders().set("X-Received-Type", type);
                String wrapper = exchange.getRequestHeaders().getFirst("X-Host-Wrapper");
                if (wrapper != null) exchange.getResponseHeaders().set("X-Received-Wrapper", wrapper);
            } else if (path.equals("/base/cookies")) {
                exchange.getResponseHeaders().add("Set-Cookie", "first=1; Path=/");
                exchange.getResponseHeaders().add("Set-Cookie", "second=2; Path=/");
                body = "cookies".getBytes(StandardCharsets.US_ASCII);
            } else if (path.equals("/base/cookie-inspect")) {
                String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                body = (cookie == null ? "none" : cookie).getBytes(StandardCharsets.US_ASCII);
            } else if (path.equals("/base/include-gzip")) {
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=windows-1251");
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.getResponseHeaders().add("Set-Cookie", "include-first=1; Path=/");
                exchange.getResponseHeaders().add("Set-Cookie", "include-second=2; Path=/");
                var compressed = new ByteArrayOutputStream();
                try (var gzip = new GZIPOutputStream(compressed)) {
                    gzip.write("Привет из include".getBytes(java.nio.charset.Charset.forName("windows-1251")));
                }
                body = compressed.toByteArray();
            } else if (path.equals("/base/include-headers")) {
                for (String name : List.of("Range", "If-Range", "If-None-Match", "If-Modified-Since", "If-Match",
                        "If-Unmodified-Since", "Content-Type", "X-Unapproved"))
                    assertNull(exchange.getRequestHeaders().getFirst(name), "Outer representation header leaked: " + name);
                body = ("full-resource|" + exchange.getRequestHeaders().getFirst("Accept-Language") + "|"
                        + exchange.getRequestHeaders().getFirst("Accept-Encoding")).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("ETag", "\"origin-not-mvc\"");
            } else if (path.startsWith("/base/include-status-")) {
                int status = Integer.parseInt(path.substring("/base/include-status-".length()));
                body = "incomplete-or-not-a-resource".getBytes(StandardCharsets.UTF_8);
                if (status == 206) exchange.getResponseHeaders().set("Content-Range", "bytes 0-25/100");
                if (status == 401) exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"origin\"");
                if (status == 405) exchange.getResponseHeaders().set("Allow", "POST");
                if (status == 302) exchange.getResponseHeaders().set("Location", "/base/redirect-must-not-follow");
                exchange.getResponseHeaders().add("Set-Cookie", "failed-load=1; Path=/");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                return;
            } else body = (exchange.getRequestMethod() + "|" + exchange.getRequestURI())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Throwable failure) {
            UPSTREAM_FAILURE.compareAndSet(null, failure);
            if (failure instanceof IOException io) throw io;
            throw new IOException(failure);
        }
    }

    private static byte[] multipart() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\n"
                + "Проверка + %2F\r\n--" + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(randomBytes(65536 + 37, 67));
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    private static byte[] randomBytes(int length, int seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private record Host(Transport transport, ConfigurableApplicationContext context, int port) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class WrapperConfiguration {
        @Bean
        FilterRegistrationBean<Filter> hostWrappers() {
            Filter filter = (request, response, chain) -> {
                HttpServletRequest incoming = (HttpServletRequest)request;
                HttpServletResponse outgoing = (HttpServletResponse)response;
                if (incoming.getRequestURI().endsWith("/cookies") || incoming.getRequestURI().endsWith("/fail-text"))
                    outgoing.addHeader("Set-Cookie", "application=host; Path=/");
                if (!incoming.getRequestURI().endsWith("/wrapped-echo")) {
                    chain.doFilter(request, response);
                    return;
                }
                HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(incoming) {
                    @Override public String getHeader(String name) {
                        return name.equalsIgnoreCase("X-Host-Wrapper") ? "host-wrapper" : super.getHeader(name);
                    }
                    @Override public java.util.Enumeration<String> getHeaderNames() {
                        List<String> names = new ArrayList<>(java.util.Collections.list(super.getHeaderNames()));
                        names.add("X-Host-Wrapper");
                        return java.util.Collections.enumeration(names);
                    }
                    @Override public java.util.Enumeration<String> getHeaders(String name) {
                        return name.equalsIgnoreCase("X-Host-Wrapper")
                                ? java.util.Collections.enumeration(List.of("host-wrapper")) : super.getHeaders(name);
                    }
                };
                HttpServletResponseWrapper responseWrapper = new HttpServletResponseWrapper(outgoing) {
                    @Override public ServletOutputStream getOutputStream() throws IOException {
                        ServletOutputStream output = super.getOutputStream();
                        return new ServletOutputStream() {
                            @Override public boolean isReady() { return output.isReady(); }
                            @Override public void setWriteListener(WriteListener listener) { output.setWriteListener(listener); }
                            @Override public void write(int value) throws IOException {
                                output.write(value);
                                WRAPPED_BYTES.incrementAndGet();
                            }
                            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                                output.write(bytes, offset, length);
                                WRAPPED_BYTES.addAndGet(length);
                            }
                            @Override public void flush() throws IOException { output.flush(); }
                        };
                    }
                };
                chain.doFilter(requestWrapper, responseWrapper);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setName("hostApplicationWrappers");
            registration.addUrlPatterns("/*");
            registration.setAsyncSupported(true);
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ErrorPolicyConfiguration {
        @Bean
        ProxyRequestHandler testNewHandler(@Qualifier("jettyProxyEngine") JettyProxyEngine engine) {
            var normal = new JettyProxyHandler(engine);
            return (request, response, route) -> {
                route = variedRoute(route);
                if (!route.rawPath().equals("/fail-text")) {
                    normal.handle(request, response, route);
                    return;
                }
                engine.forward(request, response, ProxyRequestPlan.builder(route.target()).timeout(route.timeout())
                        .errorPolicy((metadata, status, failure) -> ProxyErrorResponse.text(500, "Подсистема недоступна"))
                        .build());
            };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LegacySelectionConfiguration {
        @Bean
        @Primary
        ProxyRequestHandler testLegacyHandler(@Qualifier("legacyProxyHandler") ProxyRequestHandler delegate) {
            return (request, response, route) -> delegate.handle(request, response, variedRoute(route));
        }
    }

    private static BackendRoute variedRoute(BackendRoute route) {
        if (route.rawPath().equals("/dynamic-target"))
            return new BackendRoute(route.backendId(), route.rawPath(), URI.create(route.target().toASCIIString()
                    .replace("/dynamic-target", "/selected-target")), route.timeout());
        if (route.rawPath().equals("/dynamic-timeout"))
            return new BackendRoute(route.backendId(), route.rawPath(), route.target(), Duration.ofSeconds(3));
        return route;
    }
}
