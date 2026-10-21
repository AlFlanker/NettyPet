package dev.example.proxy.transport;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Адаптер Servlet-контейнера: прервать усечённый ответ без успешного HTTP end marker.
 * Вызывается из I/O-потока под lifecycle guard обмена. Реализация обязана быть
 * неблокирующей: нельзя ждать AsyncListener callbacks, завершения Future или потоков.
 * Нельзя сохранять request для последующего доступа после возврата из метода.
 */
@FunctionalInterface
public interface DownstreamAborter {
    void abort(HttpServletRequest request, Throwable failure);
}
