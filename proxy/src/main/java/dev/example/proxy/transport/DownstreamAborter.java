package dev.example.proxy.transport;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Адаптер контейнера для прерывания неполного ответа без признака успешного окончания HTTP.
 * Прокси вызывает его под блокировкой жизненного цикла обмена, в том числе из потока I/O.
 * Реализация не должна ждать обработчики AsyncListener, завершение Future или другие потоки.
 * Сохранять request для обращения после возврата из метода запрещено.
 */
@FunctionalInterface
public interface DownstreamAborter {
    /** Прерывает ответ по указанной причине; возврат не означает завершение всей очистки обмена. */
    void abort(HttpServletRequest request, Throwable failure);
}
