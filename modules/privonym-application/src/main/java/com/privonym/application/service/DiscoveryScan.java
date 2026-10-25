package com.privonym.application.service;

import java.util.List;

public record DiscoveryScan(long dataSourceId, String connectionName, boolean rulesetExists,
        List<MaskingAlgorithmOption> algorithms, List<DiscoveryFinding> findings, List<String> warnings) {
    public DiscoveryScan {
        algorithms = List.copyOf(algorithms); findings = List.copyOf(findings); warnings = List.copyOf(warnings);
    }
    public DiscoveryScan(long dataSourceId,String connectionName,boolean rulesetExists,
            List<MaskingAlgorithmOption> algorithms,List<DiscoveryFinding> findings) {
        this(dataSourceId,connectionName,rulesetExists,algorithms,findings,List.of());
    }
}
