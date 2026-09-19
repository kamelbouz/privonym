package com.privonym.application.service;

import java.util.Objects;

public record JobSummary(long id, String reference, String status,
        String plannedAt, String startedAt, long rowsProcessed, long expectedRows,
        long rulesetId, String rulesetName,
        int progressPercent, int parallelDegree, String executionMode,
        int chunkSize, long chunkThreshold, int completedChunks, int totalChunks,
        String preScriptFileName, String postScriptFileName) {
    public JobSummary(long id, String reference, String status,
            String plannedAt, String startedAt, long rowsProcessed, long expectedRows,
            long rulesetId, String rulesetName, int progressPercent, int parallelDegree,
            String executionMode, int chunkSize, long chunkThreshold,
            int completedChunks, int totalChunks) {
        this(id, reference, status, plannedAt, startedAt, rowsProcessed, expectedRows,
                rulesetId, rulesetName, progressPercent, parallelDegree, executionMode,
                chunkSize, chunkThreshold, completedChunks, totalChunks, "", "");
    }

    public JobSummary {
        if (id < 1) {
            throw new IllegalArgumentException("job id must be positive");
        }
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(status, "status");
        plannedAt = plannedAt == null ? "" : plannedAt;
        startedAt = startedAt == null ? "" : startedAt;
        if (rowsProcessed < 0 || expectedRows < 0) {
            throw new IllegalArgumentException("row counts must not be negative");
        }
        if (rulesetId < 1) {
            throw new IllegalArgumentException("rulesetId must be positive");
        }
        Objects.requireNonNull(rulesetName, "rulesetName");
        if (progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("progressPercent must be between 0 and 100");
        }
        if (parallelDegree < 1 || parallelDegree > 128) {
            throw new IllegalArgumentException("parallelDegree must be between 1 and 128");
        }
        Objects.requireNonNull(executionMode, "executionMode");
        preScriptFileName = preScriptFileName == null ? "" : preScriptFileName;
        postScriptFileName = postScriptFileName == null ? "" : postScriptFileName;
        if (chunkSize < 1 || chunkThreshold < chunkSize
                || completedChunks < 0 || totalChunks < completedChunks) {
            throw new IllegalArgumentException("Invalid chunk execution summary");
        }
    }
}
