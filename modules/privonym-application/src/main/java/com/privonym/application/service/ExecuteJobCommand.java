package com.privonym.application.service;

import com.privonym.domain.execution.ExecutionMode;
import com.privonym.domain.execution.RecoveryMode;
import com.privonym.domain.job.JobId;
import java.util.Objects;

public record ExecuteJobCommand(JobId jobId, ExecutionMode mode, RecoveryMode recoveryMode) {
    public ExecuteJobCommand {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(recoveryMode, "recoveryMode");
    }
}
