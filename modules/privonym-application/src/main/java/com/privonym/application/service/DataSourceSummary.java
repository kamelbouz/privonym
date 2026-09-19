package com.privonym.application.service;

import java.util.Objects;

public record DataSourceSummary(long id, String name, boolean enabled, String technology,
        String connectorStatus, String host, int port, String service, String username,
        int schemaCount, int tableCount, int jobCount) {
    public DataSourceSummary {
        if (id < 1 || port < 0 || schemaCount < 0 || tableCount < 0 || jobCount < 0) {
            throw new IllegalArgumentException("Invalid data source values");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(technology, "technology");
        Objects.requireNonNull(connectorStatus, "connectorStatus");
        host = host == null ? "" : host;
        service = service == null ? "" : service;
        username = username == null ? "" : username;
    }
}
