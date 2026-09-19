package com.privonym.domain.job;

import java.util.Objects;

public record PlanChecksum(String value) {
    public PlanChecksum {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Plan checksum must be lowercase SHA-256");
        }
    }
}
