package com.privonym.application.service;

import com.privonym.application.port.in.PreflightJobUseCase;
import com.privonym.application.port.out.ApplicationClock;
import com.privonym.application.port.out.AuditEventSink;
import com.privonym.application.port.out.JobRepository;
import com.privonym.application.port.out.PlanIntegrityChecker;
import com.privonym.application.port.out.TargetPreflightAdapter;
import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.JobStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PreflightJobService implements PreflightJobUseCase {
    private final JobRepository jobs;
    private final PlanIntegrityChecker planIntegrity;
    private final TargetPreflightAdapter target;
    private final AuditEventSink audit;
    private final ApplicationClock clock;

    public PreflightJobService(JobRepository jobs, PlanIntegrityChecker planIntegrity,
            TargetPreflightAdapter target, AuditEventSink audit, ApplicationClock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.planIntegrity = Objects.requireNonNull(planIntegrity, "planIntegrity");
        this.target = Objects.requireNonNull(target, "target");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PreflightJobResult preflight(PreflightJobCommand command) {
        Objects.requireNonNull(command, "command");
        AnonymizationJob checking = jobs.get(command.jobId())
                .transitionTo(JobStatus.PREFLIGHTING);
        jobs.save(checking);
        try {
            List<String> messages = new ArrayList<>();
            messages.addAll(planIntegrity.verify(checking.id()));
            messages.addAll(target.verify(checking.dataSourceId(), checking.id()));
            AnonymizationJob passed = checking.transitionTo(JobStatus.PREFLIGHT_PASSED);
            jobs.save(passed);
            audit.append(new ApplicationAuditEvent(passed.id(), "JOB_PREFLIGHT_PASSED",
                    "Validated " + messages.size() + " control(s)", clock.now()));
            return new PreflightJobResult(passed.id(), passed.status(), messages);
        } catch (RuntimeException error) {
            AnonymizationJob failed = checking.transitionTo(JobStatus.PREFLIGHT_FAILED);
            jobs.save(failed);
            audit.append(new ApplicationAuditEvent(failed.id(), "JOB_PREFLIGHT_FAILED",
                    error.getClass().getSimpleName() + ": " + error.getMessage(), clock.now()));
            throw error;
        }
    }
}
