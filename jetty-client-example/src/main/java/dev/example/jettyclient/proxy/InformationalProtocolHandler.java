package dev.example.jettyclient.proxy;

import org.eclipse.jetty.client.ProcessingProtocolHandler;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;

/**
 * Запасной обработчик 1xx после стандартных 100/102/103.
 * ProcessingProtocolHandler сбрасывает промежуточный ответ и восстанавливает
 * слушателей обмена, позволяя получить следующий, финальный ответ.
 * Одного игнорирования 1xx в Servlet listener недостаточно для resetResponse Jetty.
 */
final class InformationalProtocolHandler extends ProcessingProtocolHandler {
    @Override
    public String getName() { return "proxy-informational"; }

    @Override
    public boolean accept(Request request, Response response) {
        int status = response.getStatus();
        // 101 изменяет протокол соединения и должен остаться ошибкой основного обмена.
        return status >= 100 && status < 200 && status != 101;
    }
}
