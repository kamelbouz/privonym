package com.privonym.application.service;

import com.privonym.domain.job.JobId;
import com.privonym.domain.job.PlanChecksum;
import java.util.List;
import java.util.Objects;

public record ExecutablePlan(JobId jobId, PlanChecksum checksum,
        List<ExecutableStep> steps) {
    public ExecutablePlan {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(checksum, "checksum");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("An executable plan requires at least one step");
        }
    }
}
