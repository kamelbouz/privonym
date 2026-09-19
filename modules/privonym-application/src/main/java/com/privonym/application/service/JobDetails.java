package com.privonym.application.service;

import java.util.List;
import java.util.Objects;

public record JobDetails(JobSummary job, long rulesetVersionId, long dataSourceId,
        String errorCode, String errorMessage, String preScriptFileName,
        String postScriptFileName, List<JobStepDetails> steps) {
    public JobDetails {
        Objects.requireNonNull(job, "job");
        if (rulesetVersionId < 1 || dataSourceId < 1) {
            throw new IllegalArgumentException("repository identifiers must be positive");
        }
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
        preScriptFileName = preScriptFileName == null ? "" : preScriptFileName;
        postScriptFileName = postScriptFileName == null ? "" : postScriptFileName;
        steps = List.copyOf(steps);
    }
}
