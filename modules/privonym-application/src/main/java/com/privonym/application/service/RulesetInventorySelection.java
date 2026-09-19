package com.privonym.application.service;

import java.util.Map;

public record RulesetInventorySelection(String targetType, long targetId,
        String algorithmCode, String criteria, Map<String, String> parameters) {
    public RulesetInventorySelection(String targetType, long targetId,
            String algorithmCode, String criteria) {
        this(targetType, targetId, algorithmCode, criteria, Map.of());
    }

    public RulesetInventorySelection {
        targetType = targetType == null ? "" : targetType.trim().toUpperCase();
        algorithmCode = algorithmCode == null ? "" : algorithmCode.trim().toUpperCase();
        criteria = criteria == null ? "" : criteria.trim();
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        if (!targetType.equals("TABLE") && !targetType.equals("COLUMN")) {
            throw new IllegalArgumentException("Inventory target must be TABLE or COLUMN");
        }
        if (targetId <= 0 || algorithmCode.isBlank()) {
            throw new IllegalArgumentException("Inventory target and algorithm are required");
        }
    }
}
