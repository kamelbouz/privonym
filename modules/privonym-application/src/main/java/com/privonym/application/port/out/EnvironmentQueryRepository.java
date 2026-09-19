package com.privonym.application.port.out;

import com.privonym.application.service.EnvironmentDetails;
import com.privonym.application.service.EnvironmentSummary;
import java.util.List;

public interface EnvironmentQueryRepository {
    List<EnvironmentSummary> list();

    EnvironmentDetails get(String code);

    EnvironmentSummary create(String code, String name, String description);
}
