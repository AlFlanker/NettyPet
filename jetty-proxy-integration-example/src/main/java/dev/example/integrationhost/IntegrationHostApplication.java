package dev.example.integrationhost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** An independent host application; its classpath contains the ordinary proxy library JARs. */
@SpringBootApplication
public class IntegrationHostApplication {
    public static void main(String[] args) { SpringApplication.run(IntegrationHostApplication.class, args); }

    @RestController
    static class LocalController {
        @GetMapping(value = "/local", produces = "text/plain")
        String local() { return "host-local"; }
    }
}
