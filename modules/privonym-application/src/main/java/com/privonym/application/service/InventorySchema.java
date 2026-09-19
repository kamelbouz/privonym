package com.privonym.application.service;

import java.util.List;
import java.util.Objects;

public record InventorySchema(String name, String synchronizedAt, List<InventoryTable> tables) {
    public InventorySchema {
        Objects.requireNonNull(name, "name");
        synchronizedAt = synchronizedAt == null ? "" : synchronizedAt;
        tables = List.copyOf(tables);
    }
}
