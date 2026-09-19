package com.privonym.domain.execution;

public record ExecutionId(long value) {
    public ExecutionId {
        if (value <= 0) {
            throw new IllegalArgumentException("ExecutionId must be positive");
        }
    }
}
