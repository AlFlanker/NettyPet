package dev.example.jettyclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.jettyclient.proxy.JettyProxyEngine;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP coverage for cookies issued by a Filter before the proxy takes ownership. */
@Timeout(20)
class ProxyFilterCookieIntegrationTest {
    private static HttpServer upstream;
    private static ExecutorService workers;
    private static ConfigurableApplicationContext application;
    private static JettyProxyEngine engine;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(workers);
        upstream.createContext("/", ProxyFilterCookieIntegrationTest::serve);
        upstream.start();
        application = new SpringApplicationBuilder(JettyClientExampleApplication.class, ApplicationCookies.class)
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--jetty.proxy.upstream=http://127.0.0.1:" + upstream.getAddress().getPort(),
                        "--jetty.proxy.exchange-timeout=5s");
        Object server = application.getClass().getMethod("getWebServer").invoke(application);
        port = (int) server.getClass().getMethod("getPort").invoke(server);
        engine = application.getBean(JettyProxyEngine.class);
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
        if (upstream != null) upstream.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    @Test
    void preservesApplicationAndRepeatedUpstreamCookiesWithoutSharingThemBetweenClients() throws Exception {
        try (HttpClient firstClient = client(); HttpClient independentClient = client()) {
            HttpResponse<String> first = firstClient.send(request("/set-cookies")
                            .header("Cookie", "caller=first").build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, first.statusCode());
            assertEquals("caller=first", first.body());
            List<String> cookies = first.headers().allValues("Set-Cookie");
            assertEquals(3, cookies.size(), "The Filter cookie and both upstream cookies must stay separate");
            assertEquals(1, cookies.stream().filter(value -> value.startsWith("application-session=portal;")).count());
            assertTrue(cookies.contains("backend-one=1; Path=/; HttpOnly"), cookies.toString());
            assertTrue(cookies.contains("backend-two=2; Path=/; SameSite=Lax"), cookies.toString());

            await().atMost(Duration.ofSeconds(2)).until(() -> engine.activeRequests() == 0);
            HttpResponse<String> second = independentClient.send(request("/cookie-inspect").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, second.statusCode());
            assertEquals("none", second.body(), "The shared upstream client must not retain any user's cookies");
            assertEquals(1, second.headers().allValues("Set-Cookie").size(),
                    "Only this request's application cookie should be issued");
            assertTrue(second.headers().firstValue("Set-Cookie").orElseThrow()
                    .startsWith("application-session=portal;"));
            await().atMost(Duration.ofSeconds(2)).until(() -> engine.activeRequests() == 0);
        }
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/proxy" + path))
                .timeout(Duration.ofSeconds(5)).GET();
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    private static void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestURI().getPath().equals("/set-cookies")) {
                exchange.getResponseHeaders().add("Set-Cookie", "backend-one=1; Path=/; HttpOnly");
                exchange.getResponseHeaders().add("Set-Cookie", "backend-two=2; Path=/; SameSite=Lax");
            }
            String receivedCookie = exchange.getRequestHeaders().getFirst("Cookie");
            byte[] body = (receivedCookie == null ? "none" : receivedCookie).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ApplicationCookies {
        @Bean
        FilterRegistrationBean<Filter> applicationCookieFilter() {
            Filter filter = (request, response, chain) -> {
                Cookie cookie = new Cookie("application-session", "portal");
                cookie.setPath("/");
                cookie.setHttpOnly(true);
                ((HttpServletResponse) response).addCookie(cookie);
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setName("applicationCookieFilter");
            registration.setUrlPatterns(List.of("/proxy", "/proxy/*"));
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setAsyncSupported(true);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
