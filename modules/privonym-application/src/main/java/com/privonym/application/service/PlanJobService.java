package com.privonym.application.service;

import com.privonym.application.port.in.PlanJobUseCase;
import com.privonym.application.port.out.ApplicationClock;
import com.privonym.application.port.out.AuditEventSink;
import com.privonym.application.port.out.JobPlanRepository;
import com.privonym.application.port.out.JobRepository;
import com.privonym.application.port.out.RulesetRepository;
import com.privonym.application.port.out.TargetDatabaseAdapter;
import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.JobStatus;
import java.util.Objects;

public final class PlanJobService implements PlanJobUseCase {
    private final JobRepository jobs;
    private final RulesetRepository rulesets;
    private final TargetDatabaseAdapter targetDatabase;
    private final JobPlanRepository plans;
    private final AuditEventSink audit;
    private final ApplicationClock clock;

    public PlanJobService(JobRepository jobs, RulesetRepository rulesets,
            TargetDatabaseAdapter targetDatabase, JobPlanRepository plans,
            AuditEventSink audit, ApplicationClock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.rulesets = Objects.requireNonNull(rulesets, "rulesets");
        this.targetDatabase = Objects.requireNonNull(targetDatabase, "targetDatabase");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PlanJobResult plan(PlanJobCommand command) {
        Objects.requireNonNull(command, "command");
        AnonymizationJob planning = jobs.get(command.jobId()).transitionTo(JobStatus.PLANNING);
        jobs.save(planning);
        try {
            rulesets.requirePublished(planning.rulesetVersionId());
            GeneratedPlan generated = targetDatabase.generatePlan(new JobPlanningRequest(
                    planning.id(), planning.reference(), planning.rulesetVersionId(),
                    planning.dataSourceId()));
            plans.save(planning.id(), generated);
            AnonymizationJob planned = planning.withPlan(generated.checksum());
            jobs.save(planned);
            audit.append(new ApplicationAuditEvent(planned.id(), "JOB_PLANNED",
                    "Generated " + generated.stepCount() + " step(s)", clock.now()));
            return new PlanJobResult(planned.id(), planned.status(),
                    generated.checksum(), generated.stepCount());
        } catch (RuntimeException error) {
            AnonymizationJob failed = planning.transitionTo(JobStatus.PLAN_FAILED);
            jobs.save(failed);
            audit.append(new ApplicationAuditEvent(failed.id(), "JOB_PLAN_FAILED",
                    error.getClass().getSimpleName(), clock.now()));
            throw error;
        }
    }
}
