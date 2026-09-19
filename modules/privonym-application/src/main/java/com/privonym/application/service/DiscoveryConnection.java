package com.privonym.application.service;

public record DiscoveryConnection(long dataSourceId, long environmentId, String name,
        String environment, String technology, String endpoint, String host, int port,
        String service, String database, String username, String schemaName,
        String secretProvider, String secretReference, boolean passwordConfigured,
        boolean enabled, boolean rulesetExists, int schemaCount, int columnCount) { }
