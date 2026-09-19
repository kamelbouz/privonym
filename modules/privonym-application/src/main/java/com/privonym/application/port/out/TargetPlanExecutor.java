package com.privonym.application.port.out;

import com.privonym.application.service.ExecutablePlan;
import com.privonym.application.service.ExecutionOutcome;

public interface TargetPlanExecutor {
    ExecutionOutcome execute(ExecutablePlan plan);
}
