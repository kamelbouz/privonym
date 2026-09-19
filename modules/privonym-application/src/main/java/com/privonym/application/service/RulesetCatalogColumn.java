package com.privonym.application.service;

import java.util.List;

public record RulesetCatalogColumn(long tableId, long columnId, String schema, String table,
        String column, String dataType, boolean tableSelected, String tableAlgorithmCode,
        String tableCriteria, boolean selected, String algorithmCode, String criteria,
        List<String> configuredParameters, List<String> compatibleAlgorithmCodes) {
    public RulesetCatalogColumn {
        configuredParameters = configuredParameters == null ? List.of()
                : List.copyOf(configuredParameters);
        compatibleAlgorithmCodes = compatibleAlgorithmCodes == null ? List.of()
                : List.copyOf(compatibleAlgorithmCodes);
    }
}
