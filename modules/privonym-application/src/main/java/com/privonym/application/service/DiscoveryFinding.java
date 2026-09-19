package com.privonym.application.service;

public record DiscoveryFinding(long columnId, String schema, String table, String column,
        String dataType, String classification, int confidence, String suggestedAlgorithm) { }
