package com.privonym.application.service;

import com.privonym.domain.job.JobId;
import com.privonym.domain.job.JobStatus;
import com.privonym.domain.job.PlanChecksum;

public record PlanJobResult(
        JobId jobId,
        JobStatus status,
        PlanChecksum checksum,
        int stepCount) {
}
