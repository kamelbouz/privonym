package com.privonym.application.port.out;

import com.privonym.application.service.ApplicationAuditEvent;
import com.privonym.application.service.ExecutablePlan;
import com.privonym.application.service.ExecutionOutcome;
import com.privonym.domain.job.AnonymizationJob;

public interface ExecutionJournal {
    void start(AnonymizationJob runningJob, ExecutablePlan plan,
            ApplicationAuditEvent event);

    void succeed(AnonymizationJob validatingJob, ExecutionOutcome outcome,
            ApplicationAuditEvent event);

    void fail(AnonymizationJob failedJob, ExecutablePlan plan, RuntimeException error,
            ApplicationAuditEvent event);

    void cancel(AnonymizationJob cancelledJob, ExecutablePlan plan,
            ApplicationAuditEvent event);
}
