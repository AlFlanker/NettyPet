package dev.example.integrationhost;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete text resource; headers describe the origin, while MVC owns status, encoding and framing. */
public record LoadedResource(int status, Map<String, List<String>> headers, String text) {
    public LoadedResource {
        Objects.requireNonNull(text);
        var snapshot = new LinkedHashMap<String, List<String>>();
        headers.forEach((name, values) -> snapshot.put(name, List.copyOf(values)));
        headers = Map.copyOf(snapshot);
    }
}
