package com.privonym.application.service;

import com.privonym.domain.job.JobId;
import com.privonym.domain.job.JobReference;
import java.util.List;

public record JobReport(JobId jobId, JobReference jobReference,
        long rowsProcessed, int succeededSteps, List<ValidationMetric> metrics,
        long executionDurationSeconds) {
    public JobReport {
        metrics = List.copyOf(metrics);
        executionDurationSeconds = Math.max(0, executionDurationSeconds);
    }

    public JobReport(JobId jobId, JobReference jobReference, long rowsProcessed,
            int succeededSteps, List<ValidationMetric> metrics) {
        this(jobId, jobReference, rowsProcessed, succeededSteps, metrics, 0);
    }
}
