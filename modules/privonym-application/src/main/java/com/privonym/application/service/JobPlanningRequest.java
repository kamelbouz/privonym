package com.privonym.application.service;

import com.privonym.domain.catalog.DataSourceId;
import com.privonym.domain.job.JobId;
import com.privonym.domain.job.JobReference;
import com.privonym.domain.policy.RulesetVersionId;
import java.util.Objects;

public record JobPlanningRequest(
        JobId jobId,
        JobReference jobReference,
        RulesetVersionId rulesetVersionId,
        DataSourceId dataSourceId) {
    public JobPlanningRequest {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(jobReference, "jobReference");
        Objects.requireNonNull(rulesetVersionId, "rulesetVersionId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
    }
}
