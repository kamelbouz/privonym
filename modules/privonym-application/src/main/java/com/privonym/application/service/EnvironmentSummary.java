package com.privonym.application.service;

import java.util.Objects;

public record EnvironmentSummary(long id, String code, String name, String status,
        String owner, int dataSourceCount, int jobCount) {
    public EnvironmentSummary {
        if (id < 1 || dataSourceCount < 0 || jobCount < 0) {
            throw new IllegalArgumentException("Invalid environment counters");
        }
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        owner = owner == null ? "" : owner;
    }
}
