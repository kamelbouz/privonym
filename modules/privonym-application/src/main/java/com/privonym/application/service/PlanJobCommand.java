package com.privonym.application.service;

import com.privonym.domain.job.JobId;
import java.util.Objects;

public record PlanJobCommand(JobId jobId) {
    public PlanJobCommand {
        Objects.requireNonNull(jobId, "jobId");
    }
}
