package com.privonym.repository.oracle;

import com.privonym.application.service.JobReport;
import com.privonym.application.service.ValidationMetric;
import com.privonym.domain.job.JobId;
import com.privonym.domain.job.JobReference;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Failure PDFs must report checkpoints without presenting stale validation totals. */
final class FailureReportSmokeCheck {
    private FailureReportSmokeCheck() { }

    static void verify() throws Exception {
        String setting = "privonym.jdbc.queryTimeoutSeconds";
        String previous = System.getProperty(setting);
        try {
            System.clearProperty(setting);
            require(OracleParallelPlanExecutor.queryTimeoutSeconds() == 0,
                    "long masking statements must not acquire an unsolicited timeout");
            System.setProperty(setting, "900");
            require(OracleParallelPlanExecutor.queryTimeoutSeconds() == 900,
                    "an explicitly configured statement deadline must be honored");
            System.setProperty(setting, "0");
            require(OracleParallelPlanExecutor.queryTimeoutSeconds() == 0, "zero disables the timeout");
            for (String invalid : List.of("-1", "86401")) {
                System.setProperty(setting, invalid);
                boolean rejected = false;
                try { OracleParallelPlanExecutor.queryTimeoutSeconds(); }
                catch (IllegalArgumentException expected) { rejected = true; }
                require(rejected, "invalid timeout must be rejected");
            }
        } finally {
            if (previous == null) System.clearProperty(setting);
            else System.setProperty(setting, previous);
        }
        var oldMetrics = List.of(new ValidationMetric("EXPECTED_OLD", 123),
                new ValidationMetric("ANONYMIZED_OLD", 123),
                new ValidationMetric("ROWS_EXPECTED_TOTAL", 123),
                new ValidationMetric("ROWS_ANONYMIZED_TOTAL", 123));
        JobReport report = new JobReport(new JobId(27), new JobReference("JOB_TEST"),
                58_000_002L, 2, oldMetrics, 2482);
        String failed = new String(ExecutionReportPdfWriter.render(report, "EXECUTION_FAILED",
                "ORA-01013", Instant.EPOCH), StandardCharsets.ISO_8859_1);
        require(failed.contains("Rows processed: 58000002"), "partial PDF must retain recorded progress");
        require(failed.contains("Completed table steps: 2"), "completed table checkpoints must remain visible");
        require(failed.contains("FINAL VALIDATION NOT CONFIRMED"), "partial execution must be explicit");
        require(!failed.contains("TABLE VALIDATION") && !failed.contains("Anonymized rows:")
                && !failed.contains("Expected rows:") && !failed.contains("OLD"),
                "failed execution must not display absent or stale validation as current totals");
        String successful = new String(ExecutionReportPdfWriter.render(report, Instant.EPOCH),
                StandardCharsets.ISO_8859_1);
        require(successful.contains("TABLE VALIDATION") && successful.contains("Expected rows: 123")
                && successful.contains("Anonymized rows: 123"),
                "successful report must preserve its validation results");
        System.out.println("Failure report and opt-in timeout smoke check passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
