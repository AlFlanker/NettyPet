package dev.example.integrationhost;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Application seam before async/body ownership; implementations do not call another handler on failure. */
@FunctionalInterface
public interface ProxyRequestHandler {
    void handle(HttpServletRequest request, HttpServletResponse response, BackendRoute route)
            throws IOException, ServletException;
}
