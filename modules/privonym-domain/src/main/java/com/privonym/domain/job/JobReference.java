package com.privonym.domain.job;

import java.util.Objects;

public record JobReference(String value) {
    public JobReference {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Z0-9][A-Z0-9_-]{2,99}")) {
            throw new IllegalArgumentException("Invalid job reference: " + value);
        }
    }
}
