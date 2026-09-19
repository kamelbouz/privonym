package com.privonym.application.service;

import com.privonym.domain.job.JobId;
import com.privonym.domain.job.JobStatus;
import java.util.List;

public record ValidateJobResult(JobId jobId, JobStatus status,
        List<String> messages, List<ValidationMetric> metrics) {
    public ValidateJobResult {
        messages = List.copyOf(messages);
        metrics = List.copyOf(metrics);
    }
}
