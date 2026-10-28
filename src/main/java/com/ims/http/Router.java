package com.ims.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal REST router: method + path pattern matching with {param} segments,
 * first registered route wins. Routes are exact-length matched.
 */
public final class Router {

    @FunctionalInterface
    public interface Handler {
        void handle(WebContext ctx) throws Exception;
    }

    private record Route(String method, String pattern, Pattern regex,
                         List<String> paramNames, Handler handler) {
    }

    public record Match(Handler handler, Map<String, String> pathParams,
                        String pattern) {
    }

    private final List<Route> routes = new ArrayList<>();

    public Router add(String method, String pattern, Handler handler) {
        List<String> paramNames = new ArrayList<>();
        String body = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        String regex = "/"
                + String.join("/",
                        java.util.Arrays.stream(body.split("/"))
                                .map(segment -> {
                                    if (segment.startsWith("{") && segment.endsWith("}")) {
                                        paramNames.add(segment.substring(1,
                                                segment.length() - 1));
                                        return "([^/]+)";
                                    }
                                    return Pattern.quote(segment);
                                })
                                .toList())
                + (pattern.endsWith("/") ? "/?" : "");
        routes.add(new Route(method.toUpperCase(), pattern,
                Pattern.compile("^" + regex + "$"), paramNames, handler));
        return this;
    }

    public Match match(String method, String path) {
        String normalized = normalize(path);
        for (Route route : routes) {
            if (!route.method().equals(method.toUpperCase())) {
                continue;
            }
            Matcher matcher = route.regex().matcher(normalized);
            if (matcher.matches()) {
                Map<String, String> params = new HashMap<>();
                for (int i = 0; i < route.paramNames().size(); i++) {
                    params.put(route.paramNames().get(i), matcher.group(i + 1));
                }
                return new Match(route.handler(), params, route.pattern());
            }
        }
        return null;
    }

    /** True when the path matches some route with a different method (405 support). */
    public boolean pathExists(String path) {
        String normalized = normalize(path);
        return !allowedMethods(normalized).isEmpty();
    }

    /**
     * All HTTP methods that have a route matching this path (any method).
     * Empty when the path matches no route at all (404 support).
     */
    public Set<String> allowedMethods(String path) {
        String normalized = normalize(path);
        Set<String> allowed = new LinkedHashSet<>();
        for (Route route : routes) {
            if (route.regex().matcher(normalized).matches()) {
                allowed.add(route.method());
            }
        }
        return allowed;
    }

    private static String normalize(String path) {
        return path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1) : path;
    }
}
