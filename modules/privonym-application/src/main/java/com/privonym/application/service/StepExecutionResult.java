package com.privonym.application.service;

public record StepExecutionResult(long jobStepId, long rowsProcessed) {
    public StepExecutionResult {
        if (jobStepId <= 0 || rowsProcessed < 0) {
            throw new IllegalArgumentException("Invalid execution result");
        }
    }
}
