package com.privonym.application.port.in;

import com.privonym.application.service.PreflightJobCommand;
import com.privonym.application.service.PreflightJobResult;

public interface PreflightJobUseCase {
    PreflightJobResult preflight(PreflightJobCommand command);
}
