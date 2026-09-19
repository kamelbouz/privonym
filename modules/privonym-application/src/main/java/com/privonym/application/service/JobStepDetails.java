package com.privonym.application.service;

import java.util.Objects;

public record JobStepDetails(int number, String type, String status, long rowsProcessed) {
    public JobStepDetails {
        if (number < 1) {
            throw new IllegalArgumentException("step number must be positive");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        if (rowsProcessed < 0) {
            throw new IllegalArgumentException("rowsProcessed must not be negative");
        }
    }
}
