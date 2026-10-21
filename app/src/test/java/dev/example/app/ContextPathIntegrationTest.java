package dev.example.app;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextPathIntegrationTest {
    @Test
    @Timeout(20)
    void stripsApplicationContextAndProxyPrefixButKeepsUpstreamBasePath() throws Exception {
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            try (exchange) {
                byte[] bytes = exchange.getRequestURI().toASCIIString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        backend.start();
        try (ConfigurableApplicationContext application = new SpringApplicationBuilder(ProxyApplication.class)
                .run("--server.port=0", "--server.servlet.context-path=/gateway",
                        "--proxy.upstream=http://127.0.0.1:" + backend.getAddress().getPort() + "/base",
                        "--proxy.io-threads=1", "--spring.main.banner-mode=off")) {
            Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
            int port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                    .version(HttpClient.Version.HTTP_1_1).build()) {
                HttpRequest proxied = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/gateway/proxy/items/a%20b?x=1&x=2&encoded=%2F"))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                HttpResponse<String> response = client.send(proxied, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("/base/items/a%20b?x=1&x=2&encoded=%2F", response.body());
                HttpResponse<String> local = client.send(HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/gateway/local")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, local.statusCode());
                assertEquals("local", local.body());
            }
        } finally {
            backend.stop(0);
        }
    }
}
