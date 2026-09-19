package com.privonym.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/** Network configuration for the embedded HTTP server. */
public record ServerConfiguration(String host, int port) {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8080;

    public ServerConfiguration {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("server.host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("server.port must be between 1 and 65535");
        }
    }

    public static ServerConfiguration load() throws IOException {
        String configuredPath = System.getenv("PRIVONYM_CONFIG");
        Path path = configuredPath == null || configuredPath.isBlank()
                ? Path.of("config", "privonym.properties")
                : Path.of(configuredPath);

        Properties properties = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
        }

        String host = properties.getProperty("server.host", DEFAULT_HOST).trim();
        String portText = properties.getProperty("server.port", Integer.toString(DEFAULT_PORT)).trim();
        try {
            return new ServerConfiguration(host, Integer.parseInt(portText));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("server.port must be an integer: " + portText, exception);
        }
    }
}
