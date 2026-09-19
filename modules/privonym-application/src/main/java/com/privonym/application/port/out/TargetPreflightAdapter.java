package com.privonym.application.port.out;

import com.privonym.domain.catalog.DataSourceId;
import com.privonym.domain.job.JobId;
import java.util.List;

public interface TargetPreflightAdapter {
    List<String> verify(DataSourceId dataSourceId, JobId jobId);
}
