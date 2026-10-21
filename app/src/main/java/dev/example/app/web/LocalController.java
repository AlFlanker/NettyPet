package dev.example.app.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LocalController {
    @GetMapping("/local")
    public String local() { return "local"; }
}
