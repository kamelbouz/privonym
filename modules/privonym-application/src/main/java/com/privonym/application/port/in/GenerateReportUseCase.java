package com.privonym.application.port.in;

import com.privonym.application.service.GenerateReportCommand;
import com.privonym.application.service.JobReport;

public interface GenerateReportUseCase {
    JobReport generate(GenerateReportCommand command);
}
