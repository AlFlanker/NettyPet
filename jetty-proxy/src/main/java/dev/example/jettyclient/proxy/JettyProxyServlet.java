package dev.example.jettyclient.proxy;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.client.HttpClient;

/** Тонкий адаптер демонстрационного /proxy к общему streaming engine. */
public final class JettyProxyServlet extends HttpServlet {
    private final JettyProxyEngine engine;
    private final URI upstream;
    private final boolean ownsEngine;

    public JettyProxyServlet(HttpClient client, JettyProxyProperties properties) {
        this(client, properties, List.of());
    }

    /** Создаёт engine, lifecycle которого принадлежит этому Servlet. */
    public JettyProxyServlet(HttpClient client, JettyProxyProperties properties,
            List<ProxyHeaderCustomizer> customizers) {
        this(new JettyProxyEngine(client, properties, customizers), properties.upstream(), true);
    }

    /** Общим engine управляет вызывающее приложение, а не этот адаптер. */
    public JettyProxyServlet(JettyProxyEngine engine, URI upstream) {
        this(engine, upstream, false);
    }

    private JettyProxyServlet(JettyProxyEngine engine, URI upstream, boolean ownsEngine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.ownsEngine = ownsEngine;
    }

    /** Включает обмены, ожидающие возврата собственных буферов после отмены. */
    public int activeRequests() { return engine.activeRequests(); }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        URI target;
        try { target = ProxyHeaders.target(upstream, request); }
        catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            response.setContentLength(0);
            response.setHeader("Connection", "close");
            return;
        }
        engine.forward(request, response, target);
    }

    @Override
    public void destroy() {
        if (ownsEngine) engine.close();
        super.destroy();
    }
}
