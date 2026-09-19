package com.privonym.application.port.out;

import com.privonym.application.service.ApplicationAuditEvent;
import com.privonym.application.service.JobReport;
import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.JobId;

public interface JobReportRepository {
    JobReport load(JobId jobId);

    void complete(AnonymizationJob succeededJob, JobReport report,
            ApplicationAuditEvent event);
}
