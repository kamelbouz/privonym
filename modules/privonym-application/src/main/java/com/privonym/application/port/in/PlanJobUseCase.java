package com.privonym.application.port.in;

import com.privonym.application.service.PlanJobCommand;
import com.privonym.application.service.PlanJobResult;

public interface PlanJobUseCase {
    PlanJobResult plan(PlanJobCommand command);
}
