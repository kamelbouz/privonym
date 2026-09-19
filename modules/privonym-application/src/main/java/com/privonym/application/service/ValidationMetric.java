package com.privonym.application.service;

import java.util.Objects;

public record ValidationMetric(String name, long value) {
    public ValidationMetric {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 100 || value < 0) {
            throw new IllegalArgumentException("Invalid validation metric");
        }
    }
}
