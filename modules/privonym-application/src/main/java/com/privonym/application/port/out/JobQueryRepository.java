package com.privonym.application.port.out;

import com.privonym.application.service.JobDetails;
import com.privonym.application.service.JobSummary;
import java.util.List;

public interface JobQueryRepository {
    List<JobSummary> list(int limit);

    JobDetails get(String reference);
}
