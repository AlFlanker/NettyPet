package dev.example.integrationhost;

import java.net.URI;

/**
 * Illustrative application guard, separate from raw routing. Percent escapes are
 * decoded once for the policy; the exact encoded target remains in BackendRoute.
 * A real host must align this policy with its backend's path-normalization rules.
 */
final class ApplicationRoutePolicy {
    private ApplicationRoutePolicy() { }

    static boolean forbidden(BackendRoute route) {
        String policyPath = URI.create(route.rawPath()).getPath();
        return policyPath.equals("/forbidden") || policyPath.startsWith("/forbidden/");
    }
}
