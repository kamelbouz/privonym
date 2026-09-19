package com.privonym.application.service;

import com.privonym.application.port.in.QueryJobsUseCase;
import com.privonym.application.port.out.JobQueryRepository;
import java.util.List;
import java.util.Objects;

public final class JobQueryService implements QueryJobsUseCase {
    private final JobQueryRepository repository;

    public JobQueryService(JobQueryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<JobSummary> listJobs(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("Job query limit must be between 1 and 200");
        }
        return List.copyOf(repository.list(limit));
    }

    @Override
    public JobDetails getJob(String reference) {
        if (reference == null || !reference.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
            throw new IllegalArgumentException("Invalid job reference");
        }
        return repository.get(reference);
    }
}
