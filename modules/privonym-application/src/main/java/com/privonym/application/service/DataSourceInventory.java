package com.privonym.application.service;

import java.util.List;
import java.util.Objects;

public record DataSourceInventory(long dataSourceId, String dataSourceName, String technology,
        List<InventorySchema> schemas) {
    public DataSourceInventory {
        if (dataSourceId < 1) {
            throw new IllegalArgumentException("Data source id must be positive");
        }
        Objects.requireNonNull(dataSourceName, "dataSourceName");
        Objects.requireNonNull(technology, "technology");
        schemas = List.copyOf(schemas);
    }
}
