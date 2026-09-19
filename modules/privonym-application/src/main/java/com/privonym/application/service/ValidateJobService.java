package com.privonym.application.service;

import com.privonym.application.port.in.ValidateJobUseCase;
import com.privonym.application.port.out.ApplicationClock;
import com.privonym.application.port.out.JobRepository;
import com.privonym.application.port.out.TargetDataValidator;
import com.privonym.application.port.out.ValidationJournal;
import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.JobStatus;
import java.util.Objects;

public final class ValidateJobService implements ValidateJobUseCase {
    private final JobRepository jobs;
    private final TargetDataValidator target;
    private final ValidationJournal journal;
    private final ApplicationClock clock;

    public ValidateJobService(JobRepository jobs, TargetDataValidator target,
            ValidationJournal journal, ApplicationClock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.target = Objects.requireNonNull(target, "target");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ValidateJobResult validate(ValidateJobCommand command) {
        AnonymizationJob job = jobs.get(command.jobId());
        if (job.status() != JobStatus.VALIDATING
                && job.status() != JobStatus.VALIDATION_FAILED) {
            throw new IllegalStateException(
                    "Job must be VALIDATING or VALIDATION_FAILED before data validation");
        }
        try {
            ValidationOutcome outcome = target.validate(job.dataSourceId(), job.id());
            journal.succeed(job, outcome, new ApplicationAuditEvent(job.id(),
                    "JOB_VALIDATION_PASSED",
                    "Validated " + outcome.messages().size() + " control(s)", clock.now()));
            return new ValidateJobResult(job.id(), JobStatus.VALIDATING,
                    outcome.messages(), outcome.metrics());
        } catch (RuntimeException error) {
            AnonymizationJob failed = job.status() == JobStatus.VALIDATION_FAILED
                    ? job : job.transitionTo(JobStatus.VALIDATION_FAILED);
            journal.fail(failed, error, new ApplicationAuditEvent(failed.id(),
                    "JOB_VALIDATION_FAILED", error.getClass().getSimpleName()
                            + ": " + error.getMessage(), clock.now()));
            throw error;
        }
    }
}
