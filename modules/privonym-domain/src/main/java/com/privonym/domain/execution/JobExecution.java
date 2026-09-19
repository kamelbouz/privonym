package com.privonym.domain.execution;

import com.privonym.domain.job.JobId;
import com.privonym.domain.job.PlanChecksum;

import java.util.Objects;
import java.util.Optional;

public final class JobExecution {
    private final ExecutionId id;
    private final JobId jobId;
    private final PlanChecksum planChecksum;
    private final ExecutionStatus status;
    private final ExecutionId restartOf;

    private JobExecution(
            ExecutionId id,
            JobId jobId,
            PlanChecksum planChecksum,
            ExecutionStatus status,
            ExecutionId restartOf) {
        this.id = Objects.requireNonNull(id, "id");
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.planChecksum = Objects.requireNonNull(planChecksum, "planChecksum");
        this.status = Objects.requireNonNull(status, "status");
        this.restartOf = restartOf;
    }

    public static JobExecution first(
            ExecutionId id, JobId jobId, PlanChecksum planChecksum) {
        return new JobExecution(id, jobId, planChecksum, ExecutionStatus.CREATED, null);
    }

    public JobExecution resume() {
        if (status != ExecutionStatus.FAILED && status != ExecutionStatus.PAUSED) {
            throw new IllegalStateException("Only FAILED or PAUSED execution can resume");
        }
        return new JobExecution(id, jobId, planChecksum, ExecutionStatus.RUNNING, restartOf);
    }

    public JobExecution restartAs(ExecutionId newId, RestartSafety safety) {
        Objects.requireNonNull(newId, "newId");
        Objects.requireNonNull(safety, "safety");
        if (status != ExecutionStatus.FAILED && status != ExecutionStatus.CANCELLED) {
            throw new IllegalStateException("Only FAILED or CANCELLED execution can restart");
        }
        if (!safety.allowsRestart()) {
            throw new RestartNotSafe();
        }
        return new JobExecution(
                newId, jobId, planChecksum, ExecutionStatus.CREATED, id);
    }

    public JobExecution withStatus(ExecutionStatus target) {
        return new JobExecution(id, jobId, planChecksum, target, restartOf);
    }

    public ExecutionId id() {
        return id;
    }

    public JobId jobId() {
        return jobId;
    }

    public PlanChecksum planChecksum() {
        return planChecksum;
    }

    public ExecutionStatus status() {
        return status;
    }

    public Optional<ExecutionId> restartOf() {
        return Optional.ofNullable(restartOf);
    }
}
