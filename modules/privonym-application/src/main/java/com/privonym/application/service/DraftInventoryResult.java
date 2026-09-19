package com.privonym.application.service;

public record DraftInventoryResult(long importId, String importReference,
        long rulesetVersionId, String inventoryCode, int versionNumber,
        String status, int columnRulesCreated, int tableRulesCreated,
        int reviewItems, String checksum) {
}
