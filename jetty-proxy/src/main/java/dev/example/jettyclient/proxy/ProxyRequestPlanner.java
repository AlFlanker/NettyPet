package dev.example.jettyclient.proxy;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/** Прикладная подготовка выбранного маршрута перед передачей запроса транспорту. */
@FunctionalInterface
public interface ProxyRequestPlanner {
    /**
     * Вызывается на исходном Servlet-потоке до startAsync. Возвращает null для
     * локального запроса или готовый plan с адресом из доверенной конфигурации.
     * Здесь можно синхронно получить backend, request attributes и данные внешних
     * сервисов; нельзя читать тело, параметры форм или multipart parts.
     * Расширения plan должны захватывать подготовленные данные, а не Servlet.
     */
    ProxyRequestPlan prepare(HttpServletRequest request) throws IOException, ServletException;
}
