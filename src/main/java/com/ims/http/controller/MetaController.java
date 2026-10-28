package com.ims.http.controller;

import com.ims.http.Router;
import com.ims.http.WebContext;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/meta (public) - build/runtime information for support and
 * infrastructure checks. Exposes nothing sensitive: no config values,
 * no database coordinates, no user information.
 */
public final class MetaController {

    private final String appName;
    private final String version;
    private final Map<String, Boolean> features;

    public MetaController(String appName, String version,
                          Map<String, Boolean> features) {
        this.appName = appName;
        this.version = version;
        this.features = Map.copyOf(features);
    }

    public void register(Router router) {
        router.add("GET", "/api/meta", this::meta);
    }

    private void meta(WebContext ctx) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", appName);
        body.put("version", version);
        body.put("apiVersion", "v1");
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        Map<String, Object> vm = new LinkedHashMap<>();
        vm.put("vendor", System.getProperty("java.vm.vendor"));
        vm.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
        body.put("jvm", vm);
        body.put("features", features);
        ctx.ok(body);
    }
}
