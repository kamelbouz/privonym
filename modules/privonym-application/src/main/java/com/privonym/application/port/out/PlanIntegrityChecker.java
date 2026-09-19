package com.privonym.application.port.out;

import com.privonym.domain.job.JobId;
import java.util.List;

public interface PlanIntegrityChecker {
    List<String> verify(JobId jobId);
}
