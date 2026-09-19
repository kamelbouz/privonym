package com.privonym.application.service;

import java.util.List;
import java.util.Objects;

public record InventoryTable(long id, String name, String objectType, Long estimatedRows,
        boolean enabled, String synchronizedAt, List<InventoryColumn> columns) {
    public InventoryTable {
        if (id < 1 || estimatedRows != null && estimatedRows < 0) {
            throw new IllegalArgumentException("Invalid inventory table values");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(objectType, "objectType");
        synchronizedAt = synchronizedAt == null ? "" : synchronizedAt;
        columns = List.copyOf(columns);
    }
}
