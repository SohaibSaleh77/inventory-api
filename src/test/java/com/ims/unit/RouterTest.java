package com.ims.unit;

import com.ims.http.Router;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Router matching semantics incl. path params and normalization. */
class RouterTest {

    private int hits = 0;

    @Test
    void matchesExactPaths() {
        Router router = new Router();
        router.add("GET", "/api/health", ctx -> hits++);
        assertNotNull(router.match("GET", "/api/health"));
        assertNull(router.match("GET", "/api/healthx"));
        assertNull(router.match("POST", "/api/health"));
    }

    @Test
    void extractsPathParams() {
        Router router = new Router();
        router.add("GET", "/api/users/{id}/orders/{orderId}", ctx -> {
        });
        Router.Match match = router.match("GET", "/api/users/42/orders/99");
        assertNotNull(match);
        Map<String, String> params = match.pathParams();
        assertEquals("42", params.get("id"));
        assertEquals("99", params.get("orderId"));
    }

    @Test
    void paramDoesNotSpanSlashes() {
        Router router = new Router();
        router.add("GET", "/api/x/{id}", ctx -> {
        });
        assertNull(router.match("GET", "/api/x/1/2"), "param must not swallow slashes");
    }

    @Test
    void trailingSlashIsNormalized() {
        Router router = new Router();
        router.add("GET", "/api/health", ctx -> {
        });
        assertNotNull(router.match("GET", "/api/health/"));
    }

    @Test
    void firstMatchingRouteWins() throws Exception {
        Router router = new Router();
        router.add("GET", "/api/prefix/{x}", ctx -> hits = 1);
        router.add("GET", "/api/prefix/static", ctx -> hits = 2);
        Router.Match match = router.match("GET", "/api/prefix/static");
        match.handler().handle(null);
        assertEquals(1, hits, "registration order decides");
    }

    @Test
    void pathExistsSupports405() {
        Router router = new Router();
        router.add("GET", "/api/users", ctx -> {
        });
        assertTrue(router.pathExists("/api/users"));
        assertFalse(router.pathExists("/api/unknown"));
    }

    @Test
    void staticSegmentsEscapeRegex() {
        Router router = new Router();
        router.add("GET", "/api/v1.0/items", ctx -> {
        });
        assertNull(router.match("GET", "/api/v1x0/items"),
                "the dot in 'v1.0' must be literal");
        assertNotNull(router.match("GET", "/api/v1.0/items"));
    }
}
