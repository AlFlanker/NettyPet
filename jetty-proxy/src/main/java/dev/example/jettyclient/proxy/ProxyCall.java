package dev.example.jettyclient.proxy;

import java.util.concurrent.CompletionStage;

/** Управление одним обменом без доступа к Servlet или сетевым буферам. */
public interface ProxyCall {
    /**
     * Результат публикуется один раз после завершения обмена и освобождения
     * принадлежащих ему ресурсов. Servlet к этому моменту мог быть переработан
     * контейнером: callback не должен обращаться к request/response.
     * Callback может выполняться на владельце обмена и обязан быстро завершаться
     * без блокировок. Для более дорогой работы используйте whenCompleteAsync
     * со своим ограниченным executor и только подготовленными данными.
     */
    CompletionStage<ProxyExchangeResult> completion();

    /** Отменяет только этот обмен; после завершения вызов безопасен. */
    void cancel(Throwable cause);
}
