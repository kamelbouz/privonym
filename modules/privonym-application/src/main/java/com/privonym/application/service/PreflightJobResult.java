package com.privonym.application.service;

import com.privonym.domain.job.JobId;
import com.privonym.domain.job.JobStatus;
import java.util.List;

public record PreflightJobResult(JobId jobId, JobStatus status, List<String> messages) {
    public PreflightJobResult {
        messages = List.copyOf(messages);
    }
}
