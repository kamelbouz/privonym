package com.privonym.application.port.in;

import com.privonym.application.service.ExecuteJobCommand;
import com.privonym.application.service.ExecuteJobResult;

public interface ExecuteJobUseCase {
    ExecuteJobResult execute(ExecuteJobCommand command);
}
