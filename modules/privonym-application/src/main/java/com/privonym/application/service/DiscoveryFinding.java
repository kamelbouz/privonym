package com.privonym.application.service;

public record DiscoveryFinding(long columnId, String schema, String table, String column,
        String dataType, String classification, int confidence, String suggestedAlgorithm,
        String reason, java.util.List<String> detectedEntities, int sampledValues) {
    public DiscoveryFinding {
        detectedEntities = java.util.List.copyOf(detectedEntities);
    }
    public DiscoveryFinding(long columnId, String schema, String table, String column,
            String dataType, String classification, int confidence, String suggestedAlgorithm) {
        this(columnId,schema,table,column,dataType,classification,confidence,suggestedAlgorithm,
                "Column name heuristic; review required.",java.util.List.of(),0);
    }
}
