package com.privonym.application.service;

import com.privonym.domain.job.JobId;
import java.util.Objects;

public record ValidateJobCommand(JobId jobId) {
    public ValidateJobCommand {
        Objects.requireNonNull(jobId, "jobId");
    }
}
