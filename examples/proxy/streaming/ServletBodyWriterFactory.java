package com.otr.portal.proxy.streaming;

import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import org.eclipse.jetty.ee11.servlet.ServletApiResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextResponse;
import org.jetbrains.annotations.Nullable;

/**
 * Выбор стратегии записи тела.
 * <p>
 * Асинхронная запись в Jetty {@code HttpOutput} выбирается, если запрос в асинхронном режиме и в цепочке
 * обёрток ответа нет перехватчиков вывода. Обёртки Spring Security, которые только отслеживают коммит
 * ({@code OnCommittedResponseWrapper}), безопасно обходить: при асинхронной обработке их действия
 * выполняются при выходе из цепочки фильтров, до первой записи тела.
 */
@UtilityClass
public class ServletBodyWriterFactory {
    private static final String COMMIT_TRACKING_WRAPPER = "org.springframework.security.web.util.OnCommittedResponseWrapper";
    // Результат рефлексивной проверки кэшируется по классу обёртки: набор классов конечен, а запросов много.
    private static final ClassValue<Boolean> OUTPUT_INTERCEPTORS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return interceptsOutput(type);
        }
    };

    // [Шаг 7.1] Вызывается один раз за обмен, при первой записи тела.
    public static ServletBodyWriter createWriter(BodyWriterContext context) {
        ServletContextResponse jettyResponse = findAsyncWritableResponse(context.getRequest(), context.getResponse());
        if (jettyResponse != null) {
            return new AsyncJettyBodyWriter(jettyResponse, context.getListener(), context.getCallbackExecutor());
        }
        return new BlockingBodyWriter(context.getResponse(), context.getListener());
    }

    /**
     * Разворачивает цепочку обёрток ответа до Jetty {@link ServletApiResponse} и возвращает его
     * {@link ServletContextResponse}. Писать в него напрямую можно, только если ни одна обёртка не подменяет
     * поток вывода: иначе её логика (кэширование, сбор в строку) не увидела бы тело.
     * <p>
     * Сам {@link ServletContextResponse} не реализует {@link ServletResponse}: в цепочке обёрток его нет,
     * приложению Jetty 12 отдаёт фасад {@link ServletApiResponse}.
     *
     * @return Jetty-ответ для асинхронной записи или {@code null}, если писать нужно через обёртки
     */
    @Nullable
    static ServletContextResponse findAsyncWritableResponse(HttpServletRequest request, ServletResponse response) {
        if (!request.isAsyncStarted()) {
            return null;
        }
        ServletResponse current = response;
        // надеюсь, что спринг не делает бесконечных вложений
        while (current instanceof ServletResponseWrapper) {
            if (Boolean.TRUE.equals(OUTPUT_INTERCEPTORS.get(current.getClass()))) {
                return null;
            }
            current = ((ServletResponseWrapper) current).getResponse();
        }
        return current instanceof ServletApiResponse
                ? ((ServletApiResponse) current).getServletChannel().getServletContextResponse()
                : null;
    }

    /**
     * Обёртка считается перехватчиком, если переопределяет {@code getOutputStream} или {@code getWriter}
     * в классе, отличном от базового {@link ServletResponseWrapper} и обёртки Spring Security.
     * Неизвестная обёртка по умолчанию считается опасной.
     */
    private static boolean interceptsOutput(Class<?> wrapperType) {
        return !isOutputDeclaredBySafeClass(wrapperType, "getOutputStream")
                || !isOutputDeclaredBySafeClass(wrapperType, "getWriter");
    }

    private static boolean isOutputDeclaredBySafeClass(Class<?> wrapperType, String methodName) {
        try {
            Class<?> declaringClass = wrapperType.getMethod(methodName).getDeclaringClass();
            return declaringClass == ServletResponseWrapper.class
                    || COMMIT_TRACKING_WRAPPER.equals(declaringClass.getName());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
