package com.privonym.domain.job;

import com.privonym.domain.catalog.DataSourceId;
import com.privonym.domain.policy.RulesetVersionId;
import java.util.Objects;
import java.util.Optional;

public final class AnonymizationJob {
    private final JobId id;
    private final JobReference reference;
    private final RulesetVersionId rulesetVersionId;
    private final DataSourceId dataSourceId;
    private final JobStatus status;
    private final PlanChecksum planChecksum;

    private AnonymizationJob(
            JobId id,
            JobReference reference,
            RulesetVersionId rulesetVersionId,
            DataSourceId dataSourceId,
            JobStatus status,
            PlanChecksum planChecksum) {
        this.id = Objects.requireNonNull(id, "id");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.rulesetVersionId = Objects.requireNonNull(rulesetVersionId, "rulesetVersionId");
        this.dataSourceId = Objects.requireNonNull(dataSourceId, "dataSourceId");
        this.status = Objects.requireNonNull(status, "status");
        this.planChecksum = planChecksum;
        if (requiresPlan(status) && planChecksum == null) {
            throw new IllegalArgumentException("Status " + status + " requires a plan checksum");
        }
    }

    public static AnonymizationJob draft(
            JobId id,
            JobReference reference,
            RulesetVersionId rulesetVersionId,
            DataSourceId dataSourceId) {
        return new AnonymizationJob(
                id, reference, rulesetVersionId, dataSourceId, JobStatus.DRAFT, null);
    }

    public static AnonymizationJob restore(
            JobId id,
            JobReference reference,
            RulesetVersionId rulesetVersionId,
            DataSourceId dataSourceId,
            JobStatus status,
            PlanChecksum planChecksum) {
        return new AnonymizationJob(
                id, reference, rulesetVersionId, dataSourceId, status, planChecksum);
    }

    public AnonymizationJob withPlan(PlanChecksum checksum) {
        Objects.requireNonNull(checksum, "checksum");
        if (status != JobStatus.PLANNING && status != JobStatus.PLAN_FAILED) {
            throw new InvalidJobTransition(status, JobStatus.PLANNED);
        }
        return new AnonymizationJob(
                id, reference, rulesetVersionId, dataSourceId, JobStatus.PLANNED, checksum);
    }

    public AnonymizationJob transitionTo(JobStatus target) {
        Objects.requireNonNull(target, "target");
        if (!status.canTransitionTo(target)) {
            throw new InvalidJobTransition(status, target);
        }
        return new AnonymizationJob(
                id, reference, rulesetVersionId, dataSourceId, target, planChecksum);
    }

    public JobId id() {
        return id;
    }

    public JobReference reference() {
        return reference;
    }

    public RulesetVersionId rulesetVersionId() {
        return rulesetVersionId;
    }

    public DataSourceId dataSourceId() {
        return dataSourceId;
    }

    public JobStatus status() {
        return status;
    }

    public Optional<PlanChecksum> planChecksum() {
        return Optional.ofNullable(planChecksum);
    }

    private static boolean requiresPlan(JobStatus status) {
        return switch (status) {
            case PLANNED, PREFLIGHTING, PREFLIGHT_PASSED, PREFLIGHT_FAILED,
                 APPROVED, RUNNING, PAUSING, PAUSED, EXECUTION_FAILED,
                 RETRYING, VALIDATING, VALIDATION_FAILED, SUCCEEDED -> true;
            default -> false;
        };
    }
}
