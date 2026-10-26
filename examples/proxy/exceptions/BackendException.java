package com.otr.portal.proxy.exceptions;

import com.otr.portal.hosts.Backend; /**
 * @author pobedenniy.alexey
 * @since 14.08.18
 */
public class BackendException extends RuntimeException {
    protected final Backend backend;

    public BackendException(Backend backend) {
        this.backend = backend;
    }
}
