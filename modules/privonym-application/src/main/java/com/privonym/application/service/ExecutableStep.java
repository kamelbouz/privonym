package com.privonym.application.service;

import com.privonym.domain.job.PlanChecksum;
import java.util.Objects;

public record ExecutableStep(long jobStepId, int stepNumber, String generatedCode,
        PlanChecksum checksum) {
    public ExecutableStep {
        if (jobStepId <= 0 || stepNumber <= 0) {
            throw new IllegalArgumentException("Execution step identifiers must be positive");
        }
        Objects.requireNonNull(generatedCode, "generatedCode");
        Objects.requireNonNull(checksum, "checksum");
    }
}
