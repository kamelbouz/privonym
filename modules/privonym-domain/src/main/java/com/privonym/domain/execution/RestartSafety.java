package com.privonym.domain.execution;

import java.util.Objects;

public record RestartSafety(
        boolean targetRestored,
        String restoreReference,
        boolean noCommittedTransformation,
        boolean allCommittedTransformationsIdempotent) {

    public RestartSafety {
        if (targetRestored) {
            Objects.requireNonNull(restoreReference, "restoreReference");
            if (restoreReference.isBlank()) {
                throw new IllegalArgumentException("restoreReference must not be blank");
            }
        } else if (restoreReference != null) {
            throw new IllegalArgumentException(
                    "restoreReference is allowed only when targetRestored is true");
        }
    }

    public boolean allowsRestart() {
        return targetRestored
                || noCommittedTransformation
                || allCommittedTransformationsIdempotent;
    }
}
