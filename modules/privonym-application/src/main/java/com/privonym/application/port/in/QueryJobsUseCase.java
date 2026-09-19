package com.privonym.application.port.in;

import com.privonym.application.service.JobDetails;
import com.privonym.application.service.JobSummary;
import java.util.List;

/** Read-only queries used by administration adapters. */
public interface QueryJobsUseCase {
    List<JobSummary> listJobs(int limit);

    JobDetails getJob(String reference);
}
