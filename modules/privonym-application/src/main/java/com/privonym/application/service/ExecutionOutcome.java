package com.privonym.application.service;

import java.util.List;

public record ExecutionOutcome(List<StepExecutionResult> steps) {
    public ExecutionOutcome {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Execution outcome requires results");
        }
    }

    public long totalRowsProcessed() {
        return steps.stream().mapToLong(StepExecutionResult::rowsProcessed).sum();
    }
}
