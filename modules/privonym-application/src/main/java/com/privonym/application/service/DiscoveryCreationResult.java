package com.privonym.application.service;

public record DiscoveryCreationResult(long rulesetId, long versionId, String code,
        int rulesCreated) { }
