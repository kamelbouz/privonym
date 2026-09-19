package com.privonym.application.service;

public record RulesetCloneResult(long rulesetId, long versionId, long sourceVersionId,
        int columnRulesCloned, int tableRulesCloned, int skippedRules) { }
