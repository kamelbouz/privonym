package com.privonym.application.service;

public record RulesetSummary(long id, String code, String name, String description,
        long dataSourceId, String dataSourceName, String technology, String environmentCode,
        String environmentName, int versionCount, int inventoryCount, int jobCount) {
}
