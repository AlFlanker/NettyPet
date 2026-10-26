package com.otr.portal.proxy;

import com.otr.portal.proxy.headers.PortalHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class NettyHeadersAdapter implements PortalHeaders {
    private final HttpHeaders delegate;

    @Override
    public void add(String name, String value) {
        delegate.add(name, value);
    }

    @Override
    public boolean contains(String name) {
        return delegate.contains(name);
    }

    @Override
    public void set(String name, String value) {
        delegate.set(name, value);
    }
}
