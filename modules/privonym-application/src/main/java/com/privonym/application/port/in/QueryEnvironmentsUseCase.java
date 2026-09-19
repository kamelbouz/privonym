package com.privonym.application.port.in;

import com.privonym.application.service.EnvironmentDetails;
import com.privonym.application.service.EnvironmentSummary;
import java.util.List;

public interface QueryEnvironmentsUseCase {
    List<EnvironmentSummary> listEnvironments();

    EnvironmentDetails getEnvironment(String code);

    EnvironmentSummary createEnvironment(String code, String name, String description);
}
