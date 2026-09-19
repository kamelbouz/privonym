package com.privonym.application.port.out;

import com.privonym.application.service.ExecutablePlan;
import com.privonym.domain.job.JobId;

public interface SavedPlanLoader {
    ExecutablePlan loadExecutablePlan(JobId jobId);
}
