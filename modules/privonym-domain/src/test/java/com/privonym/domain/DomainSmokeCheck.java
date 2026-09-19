package com.privonym.domain;

import com.privonym.domain.catalog.DataSourceId;
import com.privonym.domain.execution.ExecutionId;
import com.privonym.domain.execution.ExecutionStatus;
import com.privonym.domain.execution.JobExecution;
import com.privonym.domain.execution.RestartNotSafe;
import com.privonym.domain.execution.RestartSafety;
import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.InvalidJobTransition;
import com.privonym.domain.job.JobId;
import com.privonym.domain.job.JobReference;
import com.privonym.domain.job.JobStatus;
import com.privonym.domain.job.PlanChecksum;
import com.privonym.domain.policy.RulesetVersionId;

public final class DomainSmokeCheck {
    private static final PlanChecksum CHECKSUM = new PlanChecksum(
            "7c10d88ca9b5225fdb6208259713c854369fa5efbcb151854432ae76f1fe57a2");

    private DomainSmokeCheck() {
    }

    public static void main(String[] args) {
        AnonymizationJob job = AnonymizationJob.draft(
                new JobId(1), new JobReference("FORMATION-EMP-001"),
                new RulesetVersionId(101), new DataSourceId(201));
        job = job.transitionTo(JobStatus.PLANNING).withPlan(CHECKSUM);
        job = job.transitionTo(JobStatus.PREFLIGHTING)
                .transitionTo(JobStatus.PREFLIGHT_PASSED)
                .transitionTo(JobStatus.APPROVED)
                .transitionTo(JobStatus.RUNNING);
        assertEquals(JobStatus.RUNNING, job.status(), "job status");

        expect(InvalidJobTransition.class,
                () -> AnonymizationJob.draft(
                        new JobId(2), new JobReference("INVALID-JOB"),
                        new RulesetVersionId(102), new DataSourceId(202))
                        .transitionTo(JobStatus.RUNNING));

        JobExecution failed = JobExecution.first(
                        new ExecutionId(10), job.id(), CHECKSUM)
                .withStatus(ExecutionStatus.FAILED);
        JobExecution resumed = failed.resume();
        assertEquals(failed.id(), resumed.id(), "resume keeps execution id");

        expect(RestartNotSafe.class,
                () -> failed.restartAs(
                        new ExecutionId(11),
                        new RestartSafety(false, null, false, false)));

        JobExecution restarted = failed.restartAs(
                new ExecutionId(12),
                new RestartSafety(true, "SNAPSHOT-EMP-001", false, false));
        assertEquals(failed.id(), restarted.restartOf().orElseThrow(),
                "restart links previous execution");
        assertEquals(ExecutionStatus.CREATED, restarted.status(), "restart status");

        System.out.println("Privonym domain smoke check passed.");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return;
            }
            throw new AssertionError("Unexpected exception: " + error, error);
        }
        throw new AssertionError("Expected exception: " + type.getSimpleName());
    }
}
