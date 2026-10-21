package dev.example.app.command;

import com.otr.portal.command.execution.CommandCancelledException;
import com.otr.portal.command.execution.CommandNotExecutedException;
import dev.example.proxy.transport.ProxyEngine;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;

/** Преобразует отказы команды в HTTP-ответ до передачи запроса транспорту. */
public final class ProxyRequestHandler {
    private final ProxyEngine engine;
    private final ProxyCommands commands;

    public ProxyRequestHandler(ProxyEngine engine, ProxyCommands commands) {
        this.engine = engine;
        this.commands = commands;
    }

    /**
     * Запускает защищённую команду на потоке Servlet. После передачи владения ответом
     * транспорту только он завершает обмен; при отказе до передачи формируется пустой HTTP-ответ.
     *
     * @param rawPath исходный путь после удаления префикса маршрута прокси
     */
    public void forward(HttpServletRequest request, HttpServletResponse response, String rawPath) throws IOException {
        var command = commands.command(engine, request, response, rawPath);
        var failure = new AtomicReference<Throwable>();
        var finished = new AtomicBoolean();
        // Подписываемся на том же потоке Servlet: до возврата из фильтра транспорт должен
        // вызвать startAsync(), либо команда должна синхронно завершиться отказом.
        command.execute().subscribe(ignored -> { }, failure::set, () -> finished.set(true));
        Throwable rejected = unwrap(failure.get());
        // Reactor передаёт некоторые Error, например AssertionError, через onError. Транспорт
        // мог уже вызвать startAsync() и прервать обмен перед выбросом ошибки. Пробрасываем
        // Error до проверки владельца и обращения к response: объект уже может быть завершён.
        if (rejected instanceof Error fatal) throw fatal;
        if (command.handedToProxy()) return;
        if (failure.get() == null && !finished.get()) {
            throw new IllegalStateException("Command returned without starting or rejecting");
        }
        // Транспорт не принял запрос; только в этом случае обработчик сам формирует HTTP-отказ.
        response.setStatus(rejected == null ? 500 : rejectionStatus(rejected));
        response.setHeader("Connection", "close");
        response.setContentLength(0);
    }

    /** Снимает обёртки CompletionException, ограничивая глубину обхода цепочки причин. */
    private static Throwable unwrap(Throwable failure) {
        for (int depth = 0; failure instanceof CompletionException && failure.getCause() != null && depth < 16; depth++)
            failure = failure.getCause();
        return failure;
    }

    /** Выбирает код отказа до запуска транспорта: 504 для таймаута, 503 для недопуска, иначе 500. */
    private static int rejectionStatus(Throwable failure) {
        if (failure instanceof TimeoutException) return 504;
        if (failure instanceof BulkheadFullException || failure instanceof CallNotPermittedException
                || failure instanceof CommandNotExecutedException || failure instanceof CommandCancelledException
                || failure instanceof CancellationException) return 503;
        return 500;
    }
}
