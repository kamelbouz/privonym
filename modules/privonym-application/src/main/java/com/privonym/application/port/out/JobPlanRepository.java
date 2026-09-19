package com.privonym.application.port.out;

import com.privonym.application.service.GeneratedPlan;
import com.privonym.domain.job.JobId;

public interface JobPlanRepository {
    void save(JobId jobId, GeneratedPlan plan);
}
