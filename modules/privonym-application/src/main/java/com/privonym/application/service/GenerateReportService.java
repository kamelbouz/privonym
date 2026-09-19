package com.privonym.application.service;

import com.privonym.application.port.in.GenerateReportUseCase;
import com.privonym.application.port.out.ApplicationClock;
import com.privonym.application.port.out.JobReportRepository;
import com.privonym.application.port.out.JobRepository;
import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.JobStatus;
import java.util.Objects;

public final class GenerateReportService implements GenerateReportUseCase {
    private final JobRepository jobs;
    private final JobReportRepository reports;
    private final ApplicationClock clock;

    public GenerateReportService(JobRepository jobs, JobReportRepository reports,
            ApplicationClock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public JobReport generate(GenerateReportCommand command) {
        AnonymizationJob job = jobs.get(command.jobId());
        if (job.status() != JobStatus.VALIDATING) {
            throw new IllegalStateException("Job must be VALIDATING before final report");
        }
        JobReport report = reports.load(job.id());
        AnonymizationJob succeeded = job.transitionTo(JobStatus.SUCCEEDED);
        reports.complete(succeeded, report, new ApplicationAuditEvent(job.id(),
                "JOB_SUCCEEDED", report.rowsProcessed() + " row(s) processed", clock.now()));
        return report;
    }
}
