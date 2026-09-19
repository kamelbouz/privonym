package com.privonym.application.port.out;

import com.privonym.application.service.ApplicationAuditEvent;
import com.privonym.application.service.ValidationOutcome;
import com.privonym.domain.job.AnonymizationJob;

public interface ValidationJournal {
    void succeed(AnonymizationJob validatingJob, ValidationOutcome outcome,
            ApplicationAuditEvent event);

    void fail(AnonymizationJob failedJob, RuntimeException error,
            ApplicationAuditEvent event);
}
