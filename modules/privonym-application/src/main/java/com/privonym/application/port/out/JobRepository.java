package com.privonym.application.port.out;

import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.JobId;

public interface JobRepository {
    AnonymizationJob get(JobId id);

    void save(AnonymizationJob job);
}
