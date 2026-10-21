package dev.example.app.monitoring;

import com.sun.net.httpserver.HttpServer;
import dev.example.app.ProxyApplication;
import dev.example.proxy.api.ProxyResult;
import dev.example.resilience.CommandExecutor;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import javax.management.Attribute;
import javax.management.AttributeNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jmx.support.JmxUtils;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Реальный Boot exporter: регистрация, значения, readonly-контракт и удаление при close. */
@Timeout(30)
class ProxyDiagnosticsIntegrationTest {
    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    @Test
    void exportsActualCountersAndKeepsLocalReadinessSeparateFromBackendState() throws Exception {
        byte[] requestBody = "diagnostic-request".getBytes(StandardCharsets.US_ASCII);
        byte[] responseBody = "diagnostic-response".getBytes(StandardCharsets.US_ASCII);
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes(); // Только маленькая фиксированная test fixture.
                exchange.sendResponseHeaders(200, responseBody.length);
                exchange.getResponseBody().write(responseBody);
            }
        });
        backend.start();
        ObjectName registered;
        try (var application = start("--proxy.upstream=http://127.0.0.1:" + backend.getAddress().getPort());
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                     .connectTimeout(Duration.ofSeconds(3)).build()) {
            registered = objectName(application);
            assertTrue(server.isRegistered(registered));
            assertEquals("ACCEPTING_TRAFFIC", server.getAttribute(registered, "LocalReadiness"));
            assertEquals("CORRECT", server.getAttribute(registered, "LocalLiveness"));
            assertEquals(true, server.getAttribute(registered, "AcceptingTraffic"));
            assertEquals(1, server.getAttribute(registered, "BulkheadMaxCalls"));

            int port = webPort(application);
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/proxy/"))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertArrayEquals(responseBody, response.body());
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertEquals(0, server.getAttribute(registered, "ActiveProxyRequests"));
                assertEquals(1L, server.getAttribute(registered, "ProxyAdmittedTotal"));
                assertEquals(1L, server.getAttribute(registered, "ProxyCompletedTotal"));
                assertEquals((long) requestBody.length, server.getAttribute(registered, "UploadedBytesTotal"));
                assertEquals((long) responseBody.length, server.getAttribute(registered, "DownloadedBytesTotal"));
                assertEquals(1, server.getAttribute(registered, "BulkheadAvailableCalls"));
                assertEquals(1L, server.getAttribute(registered, "TimeLimiterSuccessTotal"));
            });
            assertTrue((long) server.getAttribute(registered, "ExchangeDurationNanosTotal") > 0);
            assertEquals(0L, server.getAttribute(registered, "ProxyFailedTotal"));

            CommandExecutor<ProxyResult> commands = commands(application);
            assertTrue(commands.bulkhead().tryAcquirePermission());
            try {
                assertFalse(commands.bulkhead().tryAcquirePermission());
                assertEquals(0, server.getAttribute(registered, "BulkheadAvailableCalls"));
                assertEquals(1L, server.getAttribute(registered, "BulkheadRejectedTotal"));
            } finally {
                commands.bulkhead().releasePermission();
            }
            commands.circuitBreaker().transitionToOpenState();
            assertFalse(commands.circuitBreaker().tryAcquirePermission());
            assertEquals("OPEN", server.getAttribute(registered, "CircuitBreakerState"));
            assertEquals(1L, server.getAttribute(registered, "CircuitBreakerRejectedTotal"));
            // OPEN — состояние backend-группы, не повод выдавать локальное приложение за мёртвое.
            assertEquals(true, server.getAttribute(registered, "AcceptingTraffic"));
            commands.timeLimiter().onError(new TimeoutException("diagnostic event"));
            commands.timeLimiter().onError(new IOException("diagnostic event"));
            assertEquals(1L, server.getAttribute(registered, "TimeLimiterTimeoutsTotal"));
            assertEquals(1L, server.getAttribute(registered, "TimeLimiterErrorsTotal"));

            AvailabilityChangeEvent.publish(application, ReadinessState.REFUSING_TRAFFIC);
            assertEquals(false, server.getAttribute(registered, "AcceptingTraffic"));
            assertEquals("REFUSING_TRAFFIC", server.getAttribute(registered, "LocalReadiness"));

            var info = server.getMBeanInfo(registered);
            assertTrue(Arrays.stream(info.getAttributes()).noneMatch(attribute -> attribute.isWritable()));
            assertTrue(Arrays.stream(info.getOperations()).allMatch(operation ->
                    operation.getName().startsWith("get") || operation.getName().startsWith("is")));
            assertThrows(AttributeNotFoundException.class, () -> server.setAttribute(registered,
                    new Attribute("ActiveProxyRequests", 99)));
        } finally {
            backend.stop(0);
        }
        assertFalse(server.isRegistered(registered), "Exporter must not retain a stopped application");
    }

    @Test
    void multipleContextsHaveDistinctMBeansAndUnregisterOnlyTheirOwnInstance() throws Exception {
        var first = start();
        ObjectName firstName = objectName(first);
        try (var second = start()) {
            ObjectName secondName = objectName(second);
            assertNotEquals(firstName, secondName);
            assertTrue(server.isRegistered(firstName));
            assertTrue(server.isRegistered(secondName));
            first.close();
            assertFalse(server.isRegistered(firstName));
            assertTrue(server.isRegistered(secondName));
            assertEquals(true, server.getAttribute(secondName, "AcceptingTraffic"));
        } finally {
            first.close();
        }
    }

    @Test
    void disablingJmxAlsoDisablesDiagnosticsBeanAndItsEventSubscribers() throws Exception {
        var pattern = new ObjectName("dev.example.proxy:type=Diagnostics,*");
        var existing = server.queryNames(pattern, null);
        try (var application = start("--spring.jmx.enabled=false")) {
            assertTrue(application.getBeansOfType(ProxyDiagnostics.class).isEmpty());
            assertEquals(existing, server.queryNames(pattern, null));
        }
    }

    private static ConfigurableApplicationContext start(String... extra) {
        var args = new java.util.ArrayList<>(java.util.List.of("--server.address=127.0.0.1",
                "--server.port=0", "--proxy.io-threads=1",
                "--proxy-command.max-concurrent=1", "--spring.main.banner-mode=off"));
        args.addAll(java.util.List.of(extra));
        return new SpringApplicationBuilder(ProxyApplication.class).run(args.toArray(String[]::new));
    }

    private static ObjectName objectName(ConfigurableApplicationContext application) throws Exception {
        return JmxUtils.appendIdentityToObjectName(new ObjectName("dev.example.proxy:type=Diagnostics"),
                application.getBean(ProxyDiagnostics.class));
    }

    private static int webPort(ConfigurableApplicationContext application) throws Exception {
        Object webServer = application.getClass().getMethod("getWebServer").invoke(application);
        return (int) webServer.getClass().getMethod("getPort").invoke(webServer);
    }

    @SuppressWarnings("unchecked")
    private static CommandExecutor<ProxyResult> commands(ConfigurableApplicationContext application) {
        return application.getBean(CommandExecutor.class);
    }
}
