package com.privonym.repository.oracle;

import java.util.List;
import java.util.Objects;

public record RepositoryPreflightResult(
        String jobReference,
        String jobStatus,
        int stepCount,
        int checkedPlanCount,
        List<String> messages) {

    public RepositoryPreflightResult {
        Objects.requireNonNull(jobReference, "jobReference");
        Objects.requireNonNull(jobStatus, "jobStatus");
        messages = List.copyOf(messages);
    }
}
