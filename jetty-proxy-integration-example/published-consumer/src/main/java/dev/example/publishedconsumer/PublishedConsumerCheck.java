package dev.example.publishedconsumer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.jettyclient.proxy.JettyProxyClientConfiguration;
import dev.example.jettyclient.proxy.JettyProxyClientFactory;
import dev.example.jettyclient.proxy.JettyProxyEngine;
import dev.example.jettyclient.proxy.JettyProxyProperties;
import dev.example.jettyclient.proxy.ProxyFetchClient;
import dev.example.jettyclient.proxy.ProxyFetchRequest;
import dev.example.jettyclient.proxy.ProxyRewriteOptions;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import org.eclipse.jetty.client.HttpClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

/** No project dependency or composite build: this main is compiled against published Maven coordinates. */
public final class PublishedConsumerCheck {
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<Throwable> ORIGIN_FAILURE = new AtomicReference<>();

    public static void main(String[] arguments) throws Exception {
        verifyOrdinaryJar(JettyProxyEngine.class, "JettyProxyEngine.class");
        verifyOrdinaryJar(JettyProxyClientConfiguration.class, "JettyProxyClientConfiguration.class");
        Path servletSource = Path.of(Servlet.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        check(servletSource.equals(Path.of(System.getProperty("published.consumer.hostServletJar"))),
                "Servlet API must come from the explicit host layer, not a library runtime dependency");
        assertAbsent("dev.example.jettyclient.JettyClientExampleApplication");
        assertAbsent("dev.example.integrationhost.IntegrationHostApplication");

        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.setExecutor(workers);
        origin.createContext("/", PublishedConsumerCheck::respond);
        origin.start();
        URI base = URI.create("http://127.0.0.1:" + origin.getAddress().getPort());
        try {
            verifyCoreFactoryAndFetch(base);
            verifyPassiveSpringClasspath();
            verifyExplicitSpringImport(base);
            check(REQUESTS.get() == 2, "Only the two explicit fetch calls may contact the origin");
            check(ORIGIN_FAILURE.get() == null, "Independent origin fixture failed: " + ORIGIN_FAILURE.get());
            System.out.println("PASS: ordinary published JARs; core factory/fetch; passive classpath; opt-in Spring; no demo routes/settings");
            System.out.println("Metadata mode: " + (Boolean.getBoolean("published.consumer.pomOnly") ? "Maven POM only" : "Gradle module metadata"));
        } finally {
            origin.stop(0);
            workers.close();
        }
    }

    private static void verifyCoreFactoryAndFetch(URI base) throws Exception {
        JettyProxyProperties properties = JettyProxyProperties.defaults();
        HttpClient client = JettyProxyClientFactory.createStarted(properties);
        try (ProxyFetchClient fetch = new ProxyFetchClient(client, properties)) {
            var result = fetch.fetchBlocking(ProxyFetchRequest.builder(base.resolve("/factory/a%20b?x=1&x=2"))
                    .timeout(Duration.ofSeconds(3)).maxWireBytes(4096).maxDecodedBytes(4096)
                    .maxTextCharacters(4096).build());
            check(result.status() == 200, "Factory fetch status");
            check(result.bodyAsString().equals("GET|/factory/a%20b?x=1&x=2"), "Factory raw path/query changed");
            check(result.headerValues("Set-Cookie").equals(List.of("first=1; Path=/", "second=2; Path=/")),
                    "Repeated cookies were lost or joined");
        } finally { client.stop(); }
    }

    private static void verifyPassiveSpringClasspath() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.scan("dev.example.jettyclient.proxy");
            context.refresh();
            check(context.getBeansOfType(HttpClient.class).isEmpty(), "Component scanning started a proxy client");
            check(context.getBeansOfType(JettyProxyEngine.class).isEmpty(), "Component scanning activated an engine");
            check(context.getBeansOfType(Filter.class).isEmpty(), "Classpath presence registered a Filter");
            check(context.getBeansOfType(Servlet.class).isEmpty(), "Classpath presence registered a Servlet");
        }
    }

    private static void verifyExplicitSpringImport(URI base) throws Exception {
        HttpClient unrelated = new HttpClient();
        HttpClient owned;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("consumer", Map.of(
                    "jetty.proxy.selectors", "1", "jetty.proxy.max-concurrent", "2",
                    "jetty.proxy.exchange-timeout", "3s", "jetty.proxy.fetch.max-concurrent", "2",
                    "jetty.proxy.rewrite.workers", "1", "jetty.proxy.rewrite.max-queued", "1",
                    "jetty.proxy.rewrite.max-buffered-bytes", "4MB")));
            context.getBeanFactory().registerSingleton("unrelatedHostHttpClient", unrelated);
            context.registerBean(ExistingHostClientUser.class);
            context.register(ConsumerConfiguration.class);
            context.refresh();
            owned = context.getBean(JettyProxyClientConfiguration.HTTP_CLIENT_BEAN_NAME, HttpClient.class);
            check(owned != unrelated && owned.isStarted() && !unrelated.isStarted(), "Client qualifiers are not isolated");
            check(context.getBean(ExistingHostClientUser.class).client() == unrelated,
                    "Import changed an existing unqualified host HttpClient injection");
            check(context.getBeansOfType(Filter.class).isEmpty(), "Transport import registered a Filter");
            check(context.getBeansOfType(Servlet.class).isEmpty(), "Transport import registered a Servlet");
            check(context.getEnvironment().getProperty("server.port") == null, "Library leaked server settings");
            check(context.getBean(JettyProxyProperties.class).maxConcurrent() == 2, "Binding did not configure core options");
            check(context.getBean(JettyProxyClientConfiguration.ENGINE_BEAN_NAME, JettyProxyEngine.class).rewriteOptions()
                    .equals(new ProxyRewriteOptions(1, 1, 4L * 1024 * 1024)), "Published rewrite binding did not configure the engine");
            ProxyFetchClient fetch = context.getBean(JettyProxyClientConfiguration.FETCH_CLIENT_BEAN_NAME, ProxyFetchClient.class);
            var result = fetch.fetchBlocking(ProxyFetchRequest.builder(base.resolve("/spring/explicit"))
                    .timeout(Duration.ofSeconds(3)).maxWireBytes(4096).maxDecodedBytes(4096).build());
            check(result.status() == 200 && result.bodyAsString().equals("GET|/spring/explicit"), "Explicit imported fetch failed");
        }
        check(owned.isStopped(), "Spring did not stop its owned client");
        check(!unrelated.isStarted(), "Library touched the unrelated host client lifecycle");
    }

    private static void verifyOrdinaryJar(Class<?> type, String simpleClassName) throws Exception {
        Path source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        check(Files.isRegularFile(source) && source.getFileName().toString().endsWith(".jar"),
                "Published dependency was not loaded from an ordinary JAR: " + source);
        try (JarFile jar = new JarFile(source.toFile())) {
            check(jar.getEntry(type.getName().replace('.', '/') + ".class") != null, simpleClassName + " is not at the JAR root");
            check(jar.stream().noneMatch(entry -> entry.getName().startsWith("BOOT-INF/")
                    || entry.getName().equals("application.yml") || entry.getName().equals("application.properties")),
                    "Library contains executable packaging or application settings: " + source);
        }
    }

    private static void assertAbsent(String className) {
        try {
            Class.forName(className, false, PublishedConsumerCheck.class.getClassLoader());
            throw new AssertionError("Unexpected demo/host application dependency: " + className);
        } catch (ClassNotFoundException expected) { }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        try (exchange) {
            byte[] body = (exchange.getRequestMethod() + "|" + exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().add("Set-Cookie", "first=1; Path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "second=2; Path=/");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Throwable failure) {
            ORIGIN_FAILURE.compareAndSet(null, failure);
            if (failure instanceof IOException io) throw io;
            throw new IOException(failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JettyProxyClientConfiguration.class)
    static class ConsumerConfiguration { }

    static final class ExistingHostClientUser {
        private final HttpClient client;
        ExistingHostClientUser(HttpClient upstream) { client = upstream; }
        HttpClient client() { return client; }
    }
}
