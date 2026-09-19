package com.privonym.application.service;

import com.privonym.domain.job.JobId;
import java.time.Instant;
import java.util.Objects;

public record ApplicationAuditEvent(
        JobId jobId,
        String eventType,
        String detail,
        Instant occurredAt) {
    public ApplicationAuditEvent {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
