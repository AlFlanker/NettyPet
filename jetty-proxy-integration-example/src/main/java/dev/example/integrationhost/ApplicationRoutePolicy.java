package dev.example.integrationhost;

import java.net.URI;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Illustrative application guard, separate from raw routing. Percent escapes are
 * decoded once for the policy; the exact encoded target remains in BackendRoute.
 * A real host must align this policy with its backend's path-normalization rules.
 */
public final class ApplicationRoutePolicy {
    private final BackendRouteResolver routes;
    private final BooleanSupplier maintenance;
    private final Predicate<BackendRoute> forbidden;

    public ApplicationRoutePolicy(BackendRouteResolver routes, BooleanSupplier maintenance,
            Predicate<BackendRoute> forbidden) {
        this.routes = Objects.requireNonNull(routes);
        this.maintenance = Objects.requireNonNull(maintenance);
        this.forbidden = Objects.requireNonNull(forbidden);
    }

    /** Shared by streaming proxy and resource includes, before transport or body ownership. */
    public BackendRoute authorize(HttpServletRequest request, String encodedPathAndQuery) {
        if (maintenance.getAsBoolean()) throw new RejectedRoute(503);
        BackendRoute route = routes.resolve(request, encodedPathAndQuery);
        if (route == null) throw new RejectedRoute(404);
        if (forbidden.test(route)) throw new RejectedRoute(403);
        return route;
    }

    static boolean forbidden(BackendRoute route) {
        String policyPath = URI.create(route.rawPath()).getPath();
        return policyPath.equals("/forbidden") || policyPath.startsWith("/forbidden/");
    }

    public static final class RejectedRoute extends RuntimeException {
        private final int status;
        private RejectedRoute(int status) { this.status = status; }
        public int status() { return status; }
    }
}
