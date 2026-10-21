package dev.example.app.config;

import dev.example.proxy.api.RequestProcessor;
import dev.example.app.web.ProxyFilter;
import dev.example.app.command.ProxyRequestHandler;
import dev.example.app.transport.JettyProxyIoAdapter;
import dev.example.proxy.transport.ProxyEngine;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Соединяет переносимый транспорт, расширения приложения и адаптер I/O контейнера Jetty. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProxyProperties.class)
public class ProxyConfiguration {
    /** Создаёт общий движок; адаптер Jetty прерывает начатый ответ при ошибке передачи тела. */
    @Bean(destroyMethod = "close")
    ProxyEngine proxyEngine(ProxyProperties properties, ObjectProvider<RequestProcessor> processors) throws Exception {
        return new ProxyEngine(properties.toOptions(), processors.orderedStream().toList(),
                (request, failure) -> ServletContextRequest.getServletContextRequest(request)
                        .getServletChannel().abort(failure), JettyProxyIoAdapter.INSTANCE);
    }

    /** Регистрирует маршрутизацию только первичного запроса, сохраняя поддержку Servlet async. */
    @Bean
    FilterRegistrationBean<ProxyFilter> proxyFilter(ProxyRequestHandler handler) {
        var registration = new FilterRegistrationBean<>(new ProxyFilter(handler));
        registration.setName("proxyRoutingFilter");
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setAsyncSupported(true);
        // Фильтр должен работать до разбора тела запроса. Фильтр аутентификации, если он нужен,
        // размещают ещё раньше: прокси не продолжает цепочку для принятого им маршрута.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
