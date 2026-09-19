package com.privonym.application.service;

import java.util.List;
import java.util.Objects;

public record EnvironmentDetails(EnvironmentSummary environment, String description,
        List<DataSourceSummary> dataSources) {
    public EnvironmentDetails {
        Objects.requireNonNull(environment, "environment");
        description = description == null ? "" : description;
        dataSources = List.copyOf(dataSources);
    }
}
