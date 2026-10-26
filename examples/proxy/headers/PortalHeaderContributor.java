package com.otr.portal.proxy.headers;

/**
 * Общий интерфейс портальных процессоров.
 * Выполняют дообогащение заголовков проксируемых запросов специфичными заголовками
 */
public interface PortalHeaderContributor {
    void enrich(PortalHeaders headers);
}
