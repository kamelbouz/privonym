package com.privonym.application.service;

import java.util.Objects;

public record InventoryColumn(String name, int position, String nativeType,
        String normalizedType, boolean nullable, boolean primaryKey, boolean unique) {
    public InventoryColumn {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(nativeType, "nativeType");
        Objects.requireNonNull(normalizedType, "normalizedType");
        if (position < 1) {
            throw new IllegalArgumentException("Column position must be positive");
        }
    }
}
