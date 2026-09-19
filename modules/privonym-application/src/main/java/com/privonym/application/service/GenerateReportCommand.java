package com.privonym.application.service;

import com.privonym.domain.job.JobId;
import java.util.Objects;

public record GenerateReportCommand(JobId jobId) {
    public GenerateReportCommand {
        Objects.requireNonNull(jobId, "jobId");
    }
}
