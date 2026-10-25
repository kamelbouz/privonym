package com.privonym.application.service;

public record DiscoveryRulesetContext(long rulesetId,String code,String name,
        int currentVersion,int nextVersion,Long draftVersionId) { }
