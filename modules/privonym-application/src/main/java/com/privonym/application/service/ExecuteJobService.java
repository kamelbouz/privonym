package com.privonym.application.service;

import com.privonym.application.port.in.ExecuteJobUseCase;
import com.privonym.application.port.out.ApplicationClock;
import com.privonym.application.port.out.ExecutionJournal;
import com.privonym.application.port.out.JobRepository;
import com.privonym.application.port.out.SavedPlanLoader;
import com.privonym.application.port.out.TargetPlanExecutor;
import com.privonym.domain.execution.ExecutionMode;
import com.privonym.domain.execution.RecoveryMode;
import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.JobStatus;
import java.util.Objects;

public final class ExecuteJobService implements ExecuteJobUseCase {
    private final JobRepository jobs;
    private final SavedPlanLoader plans;
    private final TargetPlanExecutor target;
    private final ExecutionJournal journal;
    private final ApplicationClock clock;

    public ExecuteJobService(JobRepository jobs, SavedPlanLoader plans,
            TargetPlanExecutor target, ExecutionJournal journal, ApplicationClock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.target = Objects.requireNonNull(target, "target");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ExecuteJobResult execute(ExecuteJobCommand command) {
        Objects.requireNonNull(command, "command");
        AnonymizationJob current = jobs.get(command.jobId());
        boolean resume = current.status() == JobStatus.EXECUTION_FAILED
                && command.mode() == ExecutionMode.CHUNKED
                && command.recoveryMode() == RecoveryMode.RESUME;
        if (current.status() == JobStatus.EXECUTION_FAILED && !resume) {
            throw new IllegalStateException(
                    "Failed CHUNKED job requires --resume; restart is not implemented yet");
        }
        ExecutablePlan plan = plans.loadExecutablePlan(current.id());
        if (!current.planChecksum().orElseThrow().equals(plan.checksum())) {
            throw new IllegalStateException("Executable plan checksum differs from approved plan");
        }
        AnonymizationJob running = resume
                ? current.transitionTo(JobStatus.RETRYING).transitionTo(JobStatus.RUNNING)
                : current.transitionTo(JobStatus.RUNNING);
        journal.start(running, plan, new ApplicationAuditEvent(running.id(),
                resume ? "JOB_EXECUTION_RESUMED" : "JOB_EXECUTION_STARTED",
                command.mode() + " execution " + (resume ? "resumed" : "started"),
                clock.now()));
        try {
            ExecutionOutcome outcome = target.execute(plan);
            AnonymizationJob validating = running.transitionTo(JobStatus.VALIDATING);
            journal.succeed(validating, outcome, new ApplicationAuditEvent(validating.id(),
                    "JOB_EXECUTION_SUCCEEDED",
                    outcome.totalRowsProcessed() + " row(s) processed", clock.now()));
            return new ExecuteJobResult(validating.id(), validating.status(),
                    outcome.steps().size(), outcome.totalRowsProcessed());
        } catch (JobExecutionCancelledException error) {
            AnonymizationJob cancelled = running.transitionTo(JobStatus.CANCELLED);
            journal.cancel(cancelled, plan, new ApplicationAuditEvent(cancelled.id(),
                    "JOB_EXECUTION_CANCELLED", error.getMessage(), clock.now()));
            throw error;
        } catch (RuntimeException error) {
            AnonymizationJob failed = running.transitionTo(JobStatus.EXECUTION_FAILED);
            journal.fail(failed, plan, error, new ApplicationAuditEvent(failed.id(),
                    "JOB_EXECUTION_FAILED", error.getClass().getSimpleName()
                            + ": " + error.getMessage(), clock.now()));
            throw error;
        }
    }
}
