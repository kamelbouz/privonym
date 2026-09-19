package com.privonym.domain.policy;

public record RulesetVersionId(long value) {
    public RulesetVersionId {
        if (value <= 0) {
            throw new IllegalArgumentException("Ruleset version id must be positive");
        }
    }
}
