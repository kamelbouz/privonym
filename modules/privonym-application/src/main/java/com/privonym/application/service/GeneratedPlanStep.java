package com.privonym.application.service;

import com.privonym.domain.job.PlanChecksum;
import java.util.Objects;

public record GeneratedPlanStep(
        long catalogTableId,
        String qualifiedTable,
        String generatedCode,
        PlanChecksum checksum) {
    public GeneratedPlanStep {
        if (catalogTableId <= 0) {
            throw new IllegalArgumentException("Catalog table id must be positive");
        }
        Objects.requireNonNull(qualifiedTable, "qualifiedTable");
        Objects.requireNonNull(generatedCode, "generatedCode");
        Objects.requireNonNull(checksum, "checksum");
    }
}
