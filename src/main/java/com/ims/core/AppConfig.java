package com.ims.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration resolution chain (lowest to highest precedence):
 * <ol>
 *   <li>Classpath defaults: application.properties</li>
 *   <li>External file: ./config/application.properties</li>
 *   <li>Environment variables: http.port becomes IMS_HTTP_PORT</li>
 *   <li>JVM system properties: -Dhttp.port=9090</li>
 * </ol>
 */
public final class AppConfig {

    private static final String ENV_PREFIX = "IMS_";
    private static final Path EXTERNAL_CONFIG = Path.of("config", "application.properties");

    private final Properties properties = new Properties();

    private AppConfig() {
    }

    public static AppConfig load() {
        AppConfig config = new AppConfig();
        config.loadLayer();
        return config;
    }

    private void loadLayer() {
        // 1. classpath defaults
        try (InputStream is = AppConfig.class.getResourceAsStream("/application.properties")) {
            if (is != null) {
                properties.load(is);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read classpath application.properties", e);
        }
        // 2. external file
        if (Files.exists(EXTERNAL_CONFIG)) {
            try (InputStream is = Files.newInputStream(EXTERNAL_CONFIG)) {
                properties.load(is);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + EXTERNAL_CONFIG, e);
            }
        }
        // 3. environment variables override
        properties.forEach((k, v) -> {
            String envName = ENV_PREFIX
                    + k.toString().replace('.', '_').replace('-', '_').toUpperCase();
            String envValue = System.getenv(envName);
            if (envValue != null) {
                properties.setProperty(k.toString(), envValue);
            }
        });
        // 4. system properties override everything
        properties.forEach((k, v) -> {
            String sysValue = System.getProperty(k.toString());
            if (sysValue != null) {
                properties.setProperty(k.toString(), sysValue);
            }
        });
    }

    public String getString(String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Config key " + key + " is not an int: " + value);
        }
    }

    public long getLong(String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Config key " + key + " is not a long: " + value);
        }
    }

    public double getDouble(String key, double defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Config key " + key + " is not a double: " + value);
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /** Effective HTTP bind host. */
    public String httpHost() { return getString("http.host", "127.0.0.1"); }

    public int httpPort() { return getInt("http.port", 8080); }

    public String dbUrl() { return getString("db.url", "jdbc:mysql://127.0.0.1:3306/inventory_db"); }

    public String dbUsername() { return getString("db.username", "ims_app"); }

    public String dbPassword() { return getString("db.password", ""); }
}
