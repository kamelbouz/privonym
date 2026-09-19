package com.privonym.application.service;

import java.util.List;

public record DiscoveryScan(long dataSourceId, String connectionName, boolean rulesetExists,
        List<MaskingAlgorithmOption> algorithms, List<DiscoveryFinding> findings) {
    public DiscoveryScan { algorithms = List.copyOf(algorithms); findings = List.copyOf(findings); }
}
