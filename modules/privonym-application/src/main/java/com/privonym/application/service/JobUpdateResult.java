package com.privonym.application.service;

public record JobUpdateResult(long jobId, String reference, String status,
        long rulesetId, long rulesetVersionId, boolean planReset) {
}
