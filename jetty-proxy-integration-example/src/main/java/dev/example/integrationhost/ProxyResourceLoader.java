package dev.example.integrationhost;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Separate application seam for template/include loads; unrelated to the Filter's async handler choice. */
@FunctionalInterface
public interface ProxyResourceLoader {
    LoadedResource load(String encodedPathAndQuery, Map<String, List<String>> requestHeaders)
            throws IOException, InterruptedException;
}
