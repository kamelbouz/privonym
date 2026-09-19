package com.privonym.application.service;

import java.util.Map;

/** A column assignment reconstructed from an imported masking UPDATE. */
public record SqlInventoryColumn(String name, int position, String sourceExpression,
        String algorithmCode, Map<String, String> parameters, String matchStatus,
        boolean enabled) {
    public SqlInventoryColumn {
        parameters = Map.copyOf(parameters);
    }
}
