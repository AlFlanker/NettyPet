package com.otr.portal.proxy;

/**
 * @author pobedenniy.alexey
 * @since 28.09.17
 */
@FunctionalInterface
public interface RequestCallback {
    void requestCompleted();
}
