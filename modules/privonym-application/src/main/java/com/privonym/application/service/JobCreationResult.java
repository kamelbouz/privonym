package com.privonym.application.service;

public record JobCreationResult(long jobId, String reference, String status,
        long rulesetVersionId) {
}
