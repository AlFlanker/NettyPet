package dev.example.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Запускает приложение Spring Boot со встроенным Jetty и конфигурацией потокового прокси. */
@SpringBootApplication
public class ProxyApplication {
    public static void main(String[] args) { SpringApplication.run(ProxyApplication.class, args); }
}
