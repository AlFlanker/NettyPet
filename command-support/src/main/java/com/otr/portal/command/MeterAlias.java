package com.otr.portal.command;

import io.micrometer.core.instrument.Meter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Сохраняет локальную идентичность метрики до пользовательских фильтров.
 * Вызовы измерений передаются фактическому Meter, а исходный ID позволяет удалять
 * каждый профиль отдельно, даже если фильтр объединил их в один Meter.
 */
final class MeterAlias implements InvocationHandler {
    private final Meter.Id id;
    private final Meter noop;
    private volatile Meter delegate;

    private MeterAlias(Meter.Id id, Meter delegate, Meter noop) {
        this.id = id;
        this.delegate = delegate;
        this.noop = noop;
    }

    /** Создаёт адаптер только интерфейса метрики; фильтрованный ID реального Meter остаётся скрыт от локального реестра. */
    static <M extends Meter> M create(Class<M> api, Meter.Id id, M actual, M noop) {
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, new MeterAlias(id, actual, noop)));
    }

    /** Заменяет делегат неактивной метрикой, чтобы поздний обратный вызов не удерживал и не обновлял прежнюю метрику. */
    static void detach(Meter alias) {
        MeterAlias handler = (MeterAlias) Proxy.getInvocationHandler(alias);
        handler.delegate = handler.noop;
    }

    /**
     * Сохраняет локальные ID и идентичность адаптера, а операции измерения передаёт реальному Meter.
     * Исходное исключение делегата пробрасывается без дополнительной обёртки InvocationTargetException.
     */
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
        // Фактическая метрика может принадлежать приложению или другой привязке: адаптер её не закрывает.
        if (method.getName().equals("close")) return null;
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
