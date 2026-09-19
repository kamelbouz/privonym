package com.privonym.application.service;

public record RulesetInventoryEntry(long ruleId, String tableOwner, String tableName,
        String columnName, String dataType, String algorithm, boolean masked,
        String criteria) {
}
