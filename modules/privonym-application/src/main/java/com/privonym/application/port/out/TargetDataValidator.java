package com.privonym.application.port.out;

import com.privonym.application.service.ValidationOutcome;
import com.privonym.domain.catalog.DataSourceId;
import com.privonym.domain.job.JobId;

public interface TargetDataValidator {
    ValidationOutcome validate(DataSourceId dataSourceId, JobId jobId);
}
