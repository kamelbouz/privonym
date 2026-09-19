package com.privonym.application.port.in;

import com.privonym.application.service.ValidateJobCommand;
import com.privonym.application.service.ValidateJobResult;

public interface ValidateJobUseCase {
    ValidateJobResult validate(ValidateJobCommand command);
}
