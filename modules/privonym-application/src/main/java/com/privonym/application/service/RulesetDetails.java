package com.privonym.application.service;

import java.util.List;

public record RulesetDetails(RulesetSummary ruleset, long selectedVersionId,
        List<RulesetVersionView> versions, List<RulesetInventoryEntry> inventory,
        List<RulesetJobView> jobs) {
    public RulesetDetails {
        versions = List.copyOf(versions);
        inventory = List.copyOf(inventory);
        jobs = List.copyOf(jobs);
    }
}
