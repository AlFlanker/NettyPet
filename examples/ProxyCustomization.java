package dev.example.app.custom;

import dev.example.proxy.api.RequestProcessor;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Пример не включён в Gradle source sets.
 * Для включения скопируйте в app/src/main/java/dev/example/app/custom/ProxyCustomization.java.
 */
@Configuration(proxyBeanMethods = false)
public class ProxyCustomization {
    @Bean
    @Order(10)
    RequestProcessor additionalHeaders() {
        return (incoming, outgoing, chain) -> {
            outgoing.headers().set("X-Proxy-Node", "edge-a");
            // add() сохраняет отдельные значения; set() заменяет все прежние значения.
            outgoing.headers().add("X-Proxy-Feature", "streaming");
            chain.proceed();
        };
    }

    @Bean
    @Order(20)
    RequestProcessor rewritePathPrefix() {
        return (incoming, outgoing, chain) -> {
            URI current = outgoing.uri();
            String rawPath = current.getRawPath();
            String from = "/public";
            if (rawPath.equals(from) || rawPath.startsWith(from + "/")) {
                String rewritten = "/internal" + rawPath.substring(from.length());
                // Путь и query уже закодированы: собираем URI из raw-компонентов.
                // Многокомпонентный конструктор URI повторно закодировал бы '%'.
                String rawQuery = current.getRawQuery();
                outgoing.uri(URI.create(current.getScheme() + "://" + current.getRawAuthority()
                        + rewritten + (rawQuery == null ? "" : "?" + rawQuery)));
            }
            chain.proceed();
        };
    }
}
