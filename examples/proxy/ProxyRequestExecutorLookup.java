package com.otr.portal.proxy;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * @author pobedenniy.alexey
 * @since 12.10.16
 */
@Component
public class ProxyRequestExecutorLookup {
    private ProxyRequestExecutor nettyRequestExecutor;

    public ProxyRequestExecutorLookup(
            @Qualifier("nettyRequestExecutor") ProxyRequestExecutor nettyRequestExecutor) {
        this.nettyRequestExecutor = nettyRequestExecutor;
    }

    public final ProxyRequestExecutor forRequest(String targetUrl) {
        return nettyRequestExecutor;
    }
}
