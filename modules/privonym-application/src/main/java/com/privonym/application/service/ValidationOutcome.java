package com.privonym.application.service;

import java.util.List;

public record ValidationOutcome(List<String> messages, List<ValidationMetric> metrics) {
    public ValidationOutcome {
        messages = List.copyOf(messages);
        metrics = List.copyOf(metrics);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Validation requires at least one control");
        }
    }
}
