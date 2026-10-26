package dev.example.integrationhost;

import dev.example.jettyclient.proxy.ProxyFetchClient;
import dev.example.jettyclient.proxy.ProxyFetchRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Bounded GET text fetch through the shared client; never starts Servlet async or reuses the caller's method/body. */
public final class JettyResourceLoader implements ProxyResourceLoader {
    private final ProxyFetchClient fetch;
    private static final Set<String> REQUEST_HEADERS = Set.of(
            "accept", "accept-language", "authorization", "cookie", "x-request-id");

    public JettyResourceLoader(ProxyFetchClient fetch) {
        this.fetch = fetch;
    }

    @Override public LoadedResource load(BackendRoute route, Map<String, List<String>> requestHeaders)
            throws IOException, InterruptedException {
        var headers = new LinkedHashMap<String, List<String>>();
        requestHeaders.forEach((name, values) -> {
            if (REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) headers.put(name, List.copyOf(values));
        });
        headers.put("Accept-Encoding", List.of("gzip, deflate"));
        var request = ProxyFetchRequest.builder(route.target())
                .method("GET").headers(headers).timeout(route.timeout())
                .maxWireBytes(8 * 1024 * 1024).maxDecodedBytes(16 * 1024 * 1024)
                .maxTextCharacters(8 * 1024 * 1024).decodeContentEncoding(true).build();
        var result = fetch.fetchBlocking(request);
        if (result.status() != 200)
            throw new IOException("Resource loader requires a complete 200 response; received " + result.status());
        return new LoadedResource(200, result.headers(), result.bodyAsString());
    }
}
