package dev.example.integrationhost;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** MVC owns the text response; the common application Filter has already authorized its route. */
@RestController
public final class ResourceController {
    private final ProxyResourceLoader loader;

    public ResourceController(ProxyResourceLoader loader) {
        this.loader = loader;
    }

    @RequestMapping(value = "/resource", method = {RequestMethod.GET, RequestMethod.POST}, produces = "text/plain;charset=UTF-8")
    ResponseEntity<String> resource(HttpServletRequest incoming) {
        if (!(incoming.getAttribute(HttpProxyFilter.RESOURCE_ROUTE_ATTRIBUTE) instanceof BackendRoute route))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Resource route was not authorized");
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String name : Collections.list(incoming.getHeaderNames()))
            snapshot.put(name, Collections.list(incoming.getHeaders(name)));
        try {
            LoadedResource resource = loader.load(route, snapshot);
            if (resource.status() != 200) throw new IOException("Resource loader did not return a complete 200 response");
            HttpHeaders headers = new HttpHeaders();
            resource.headers().forEach((name, values) -> {
                if (name.equalsIgnoreCase("Set-Cookie")) values.forEach(value -> headers.add(HttpHeaders.SET_COOKIE, value));
            });
            headers.setContentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8));
            return new ResponseEntity<>(resource.text(), headers, HttpStatus.OK);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resource path", invalid);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resource load interrupted", interrupted);
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Resource load failed", failure);
        }
    }
}
