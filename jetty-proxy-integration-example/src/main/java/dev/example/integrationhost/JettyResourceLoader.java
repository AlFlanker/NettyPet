package dev.example.integrationhost;

import dev.example.jettyclient.proxy.ProxyFetchClient;
import dev.example.jettyclient.proxy.ProxyFetchRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded GET text fetch through the shared client; never starts Servlet async or reuses the caller's method/body. */
public final class JettyResourceLoader implements ProxyResourceLoader {
    private final ProxyFetchClient fetch;
    private final HostProxyProperties properties;

    public JettyResourceLoader(ProxyFetchClient fetch, HostProxyProperties properties) {
        this.fetch = fetch;
        this.properties = properties;
    }

    @Override public LoadedResource load(String encodedPathAndQuery, Map<String, List<String>> requestHeaders)
            throws IOException, InterruptedException {
        var headers = new LinkedHashMap<String, List<String>>();
        requestHeaders.forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Accept-Encoding")) headers.put(name, List.copyOf(values));
        });
        headers.put("Accept-Encoding", List.of("gzip, deflate"));
        var request = ProxyFetchRequest.builder(BackendTargets.resolve(properties.upstream(), encodedPathAndQuery))
                .method("GET").headers(headers).timeout(properties.timeout())
                .maxWireBytes(8 * 1024 * 1024).maxDecodedBytes(16 * 1024 * 1024)
                .maxTextCharacters(8 * 1024 * 1024).decodeContentEncoding(true).build();
        var result = fetch.fetchBlocking(request);
        return new LoadedResource(result.status(), result.headers(), result.bodyAsString());
    }
}
