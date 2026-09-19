package com.privonym.application.service;

import com.privonym.domain.job.JobId;
import java.util.Objects;

public record PreflightJobCommand(JobId jobId) {
    public PreflightJobCommand {
        Objects.requireNonNull(jobId, "jobId");
    }
}
