package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.OracleTemplateException;
import com.privonym.adapter.oracle.SqlChecksum;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class OracleRepositoryPreflight {
    private static final String LOAD_STEPS_SQL = """
            SELECT
                j.job_reference,
                j.status AS job_status,
                s.step_number,
                s.step_type,
                s.status AS step_status,
                s.generated_code,
                s.generated_code_checksum
            FROM anonymization_job j
            JOIN job_step s ON s.job_id = j.job_id
            WHERE j.job_reference = ?
            ORDER BY s.step_number
            """;

    public RepositoryPreflightResult validate(Connection connection, String jobReference)
            throws SQLException {
        String jobStatus = null;
        int stepCount = 0;
        int checkedPlanCount = 0;
        List<String> messages = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(LOAD_STEPS_SQL)) {
            statement.setString(1, jobReference);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    stepCount++;
                    jobStatus = rows.getString("job_status");
                    int stepNumber = rows.getInt("step_number");
                    String stepType = rows.getString("step_type");
                    String stepStatus = rows.getString("step_status");

                    if ("ANONYMIZE_TABLE".equals(stepType)) {
                        String sql = rows.getString("generated_code");
                        String storedChecksum = rows.getString("generated_code_checksum");
                        if (sql == null || sql.isBlank()) {
                            throw new OracleTemplateException(
                                    "Step " + stepNumber + " has no generated SQL");
                        }
                        if (storedChecksum == null || storedChecksum.isBlank()) {
                            throw new OracleTemplateException(
                                    "Step " + stepNumber + " has no SQL checksum");
                        }
                        String actualChecksum = SqlChecksum.sha256(sql);
                        if (!actualChecksum.equals(storedChecksum)) {
                            throw new OracleTemplateException(
                                    "Checksum mismatch for step " + stepNumber
                                            + "; the saved plan may have been modified");
                        }
                        if (!"READY".equals(stepStatus)) {
                            throw new OracleTemplateException(
                                    "Anonymization step " + stepNumber
                                            + " is not READY: " + stepStatus);
                        }
                        checkedPlanCount++;
                        messages.add("Step " + stepNumber + ": SQL checksum verified ("
                                + storedChecksum + ")");
                    } else {
                        messages.add("Step " + stepNumber + ": " + stepType
                                + " is " + stepStatus);
                    }
                }
            }
        }

        if (stepCount == 0) {
            throw new OracleTemplateException("Job not found or has no steps: " + jobReference);
        }
        if (!List.of("READY", "PLANNING").contains(jobStatus)) {
            throw new OracleTemplateException(
                    "Job cannot pass repository preflight in status: " + jobStatus);
        }
        if (checkedPlanCount == 0) {
            throw new OracleTemplateException("No generated anonymization plan was found");
        }

        return new RepositoryPreflightResult(
                jobReference, jobStatus, stepCount, checkedPlanCount, messages);
    }
}
