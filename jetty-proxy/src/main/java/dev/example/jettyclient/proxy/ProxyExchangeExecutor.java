package dev.example.jettyclient.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Граница выбора транспорта до чтения тела и передачи владения запросом.
 * Приложение может выбирать старую или новую реализацию здесь; после forward
 * нельзя вызывать другой транспорт или продолжать FilterChain.
 */
@FunctionalInterface
public interface ProxyExchangeExecutor {
    /**
     * Запускает один обмен. supplied == null означает, что исполнитель сам
     * начинает Servlet async; иначе ему передаётся существующий AsyncContext.
     */
    ProxyCall forward(HttpServletRequest request, HttpServletResponse response,
            AsyncContext supplied, ProxyRequestPlan plan);
}
