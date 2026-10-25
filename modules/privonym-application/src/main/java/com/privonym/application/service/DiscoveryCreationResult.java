package com.privonym.application.service;

public record DiscoveryCreationResult(long rulesetId, long versionId, String code,
        int rulesCreated, int versionNumber, boolean newRuleset) {
    public DiscoveryCreationResult(long rulesetId,long versionId,String code,int rulesCreated) {
        this(rulesetId,versionId,code,rulesCreated,1,true);
    }
}
