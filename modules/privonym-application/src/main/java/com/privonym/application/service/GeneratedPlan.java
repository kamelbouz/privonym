package com.privonym.application.service;

import com.privonym.domain.job.PlanChecksum;
import java.util.Objects;
import java.util.List;

public record GeneratedPlan(PlanChecksum checksum, List<GeneratedPlanStep> steps) {
    public GeneratedPlan {
        Objects.requireNonNull(checksum, "checksum");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("A generated plan must contain at least one step");
        }
    }

    public int stepCount() {
        return steps.size();
    }
}
