package com.privonym.application.service;

public record MaskingInventorySummary(long rulesetVersionId, String code, String name,
        int versionNumber, String status, String createdAt, int columnRuleCount,
        int tableRuleCount, int reviewItemCount, String dataSourceName,
        String importReference) {
}
