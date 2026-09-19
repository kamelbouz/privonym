package com.privonym.domain.job;

public record JobId(long value) {
    public JobId {
        if (value <= 0) {
            throw new IllegalArgumentException("JobId must be positive");
        }
    }
}
