package dev.example.jettyclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Отдельное приложение: Filter выбирает запросы, Jetty HttpClient обращается к upstream. */
@SpringBootApplication
public class JettyClientExampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(JettyClientExampleApplication.class, args);
    }

    /** Локальный MVC-маршрут не участвует в проксировании. */
    @RestController
    static class LocalController {
        @GetMapping(value = "/local", produces = "text/plain")
        String local() { return "local"; }
    }
}
