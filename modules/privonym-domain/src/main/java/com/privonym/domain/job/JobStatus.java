package com.privonym.domain.job;

import java.util.EnumSet;
import java.util.Set;

public enum JobStatus {
    DRAFT,
    PLANNING,
    PLANNED,
    PLAN_FAILED,
    PREFLIGHTING,
    PREFLIGHT_PASSED,
    PREFLIGHT_FAILED,
    APPROVED,
    RUNNING,
    PAUSING,
    PAUSED,
    EXECUTION_FAILED,
    RETRYING,
    VALIDATING,
    VALIDATION_FAILED,
    SUCCEEDED,
    CANCELLED;

    public boolean canTransitionTo(JobStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<JobStatus> allowedTargets() {
        return switch (this) {
            case DRAFT -> EnumSet.of(PLANNING, CANCELLED);
            case PLANNING -> EnumSet.of(PLANNED, PLAN_FAILED, CANCELLED);
            case PLAN_FAILED -> EnumSet.of(PLANNING, CANCELLED);
            case PLANNED -> EnumSet.of(PLANNING, PREFLIGHTING, CANCELLED);
            case PREFLIGHTING -> EnumSet.of(PREFLIGHT_PASSED, PREFLIGHT_FAILED, CANCELLED);
            case PREFLIGHT_FAILED -> EnumSet.of(PLANNING, PREFLIGHTING, CANCELLED);
            case PREFLIGHT_PASSED -> EnumSet.of(APPROVED, RUNNING, PREFLIGHTING, CANCELLED);
            case APPROVED -> EnumSet.of(RUNNING, PREFLIGHTING, CANCELLED);
            case RUNNING -> EnumSet.of(PAUSING, EXECUTION_FAILED, VALIDATING, CANCELLED);
            case PAUSING -> EnumSet.of(PAUSED, EXECUTION_FAILED, CANCELLED);
            case PAUSED -> EnumSet.of(RETRYING, CANCELLED);
            case EXECUTION_FAILED -> EnumSet.of(RETRYING, CANCELLED);
            case RETRYING -> EnumSet.of(RUNNING, EXECUTION_FAILED, CANCELLED);
            case VALIDATING -> EnumSet.of(SUCCEEDED, VALIDATION_FAILED);
            case VALIDATION_FAILED -> EnumSet.of(RETRYING, CANCELLED);
            case SUCCEEDED, CANCELLED -> EnumSet.noneOf(JobStatus.class);
        };
    }
}
