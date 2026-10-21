package dev.example.app.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Локальный MVC-маршрут для проверки, что запросы вне префикса прокси обслуживает приложение. */
@RestController
public class LocalController {
    @GetMapping("/local")
    public String local() { return "local"; }
}
