package com.privonym.application.service;

import com.privonym.domain.job.JobId;
import com.privonym.domain.job.JobStatus;

public record ExecuteJobResult(JobId jobId, JobStatus status,
        int executedStepCount, long rowsProcessed) {
}
