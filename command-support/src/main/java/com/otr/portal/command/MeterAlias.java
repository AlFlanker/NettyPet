package com.otr.portal.command;

import io.micrometer.core.instrument.Meter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** A metrics-only local identity; registry filters apply only to the delegated actual meter. */
final class MeterAlias implements InvocationHandler {
    private final Meter.Id id;
    private final Meter noop;
    private volatile Meter delegate;

    private MeterAlias(Meter.Id id, Meter delegate, Meter noop) {
        this.id = id;
        this.delegate = delegate;
        this.noop = noop;
    }

    static <M extends Meter> M create(Class<M> api, Meter.Id id, M actual, M noop) {
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, new MeterAlias(id, actual, noop)));
    }

    static void detach(Meter alias) {
        MeterAlias handler = (MeterAlias) Proxy.getInvocationHandler(alias);
        handler.delegate = handler.noop;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "MeterAlias[" + id + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        if (method.getName().equals("getId")) return id;
        // The application owns the actual meter. A child must never close it.
        if (method.getName().equals("close")) return null;
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
