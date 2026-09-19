package com.privonym.application.port.out;

import com.privonym.application.service.GeneratedPlan;
import com.privonym.application.service.JobPlanningRequest;

public interface TargetDatabaseAdapter {
    GeneratedPlan generatePlan(JobPlanningRequest request);
}
