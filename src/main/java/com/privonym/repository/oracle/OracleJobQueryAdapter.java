package com.privonym.repository.oracle;

import com.privonym.application.port.out.JobQueryRepository;
import com.privonym.application.service.JobDetails;
import com.privonym.application.service.JobStepDetails;
import com.privonym.application.service.JobSummary;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Connection-per-query Oracle read adapter for the HTTP administration console. */
public final class OracleJobQueryAdapter implements JobQueryRepository, AutoCloseable {
    private final String jdbcUrl;
    private final String username;
    private final char[] password;
    private volatile Boolean extendedJobConfiguration;

    public OracleJobQueryAdapter(String jdbcUrl, String username, char[] password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password").clone();
    }

    @Override
    public List<JobSummary> list(int limit) {
        String sql = """
                SELECT j.job_id, j.job_reference, j.status, r.ruleset_id, r.name ruleset_name,
                       NVL((SELECT file_name FROM job_script WHERE job_id=j.job_id AND script_phase='PRE'),'') pre_script_file_name,
                       NVL((SELECT file_name FROM job_script WHERE job_id=j.job_id AND script_phase='POST'),'') post_script_file_name,
                """ + jobExecutionProjection() + """
                       TO_CHAR(j.planned_at, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF3TZH:TZM') planned_at,
                       TO_CHAR(j.started_at, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF3TZH:TZM') started_at,
                       (SELECT NVL(SUM(NVL(a.rows_processed, 0)), 0)
                          FROM job_step s
                          JOIN job_step_attempt a ON a.job_step_id = s.job_step_id
                         WHERE s.job_id = j.job_id
                           AND a.attempt_number = (SELECT MAX(x.attempt_number)
                                 FROM job_step_attempt x
                                WHERE x.job_step_id = s.job_step_id)) attempt_rows,
                       (SELECT NVL(SUM(c.rows_processed),0)
                          FROM job_step s JOIN job_chunk c ON c.job_step_id=s.job_step_id
                         WHERE s.job_id=j.job_id AND c.status='SUCCEEDED') chunk_rows,
                       (SELECT NVL(SUM(m.metric_value),0) FROM job_metric m
                         WHERE m.job_id=j.job_id AND m.metric_name='ROWS_EXPECTED') expected_rows,
                       (SELECT COUNT(*) FROM job_step s JOIN job_chunk c
                          ON c.job_step_id=s.job_step_id
                         WHERE s.job_id=j.job_id AND c.status='SUCCEEDED') completed_chunks,
                       (SELECT NVL(SUM(m.metric_value),0) FROM job_metric m
                         WHERE m.job_id=j.job_id AND m.metric_name='CHUNKS_TOTAL') total_chunks
                  FROM anonymization_job j
                  JOIN ruleset_version rv ON rv.ruleset_version_id=j.ruleset_version_id
                  JOIN ruleset r ON r.ruleset_id=rv.ruleset_id
                 ORDER BY j.job_id DESC
                 FETCH FIRST ? ROWS ONLY
                """;
        List<JobSummary> jobs = new ArrayList<>();
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    jobs.add(summary(row));
                }
            }
            return jobs;
        } catch (SQLException error) {
            throw failure("list jobs", error);
        }
    }

    @Override
    public JobDetails get(String reference) {
        String jobSql = """
                SELECT j.job_id, j.job_reference, j.status, j.ruleset_version_id,
                       r.ruleset_id, r.name ruleset_name,
                       j.data_source_id, j.error_code, j.error_message,
                       NVL((SELECT file_name FROM job_script WHERE job_id=j.job_id AND script_phase='PRE'),'') pre_script_file_name,
                       NVL((SELECT file_name FROM job_script WHERE job_id=j.job_id AND script_phase='POST'),'') post_script_file_name,
                """ + jobExecutionProjection() + """
                       TO_CHAR(j.planned_at, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF3TZH:TZM') planned_at,
                       TO_CHAR(j.started_at, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF3TZH:TZM') started_at,
                       (SELECT NVL(SUM(NVL(a.rows_processed, 0)), 0)
                          FROM job_step s
                          JOIN job_step_attempt a ON a.job_step_id = s.job_step_id
                         WHERE s.job_id = j.job_id
                           AND a.attempt_number = (SELECT MAX(x.attempt_number)
                                 FROM job_step_attempt x
                                WHERE x.job_step_id = s.job_step_id)) attempt_rows,
                       (SELECT NVL(SUM(c.rows_processed),0)
                          FROM job_step s JOIN job_chunk c ON c.job_step_id=s.job_step_id
                         WHERE s.job_id=j.job_id AND c.status='SUCCEEDED') chunk_rows,
                       (SELECT NVL(SUM(m.metric_value),0) FROM job_metric m
                         WHERE m.job_id=j.job_id AND m.metric_name='ROWS_EXPECTED') expected_rows,
                       (SELECT COUNT(*) FROM job_step s JOIN job_chunk c
                          ON c.job_step_id=s.job_step_id
                         WHERE s.job_id=j.job_id AND c.status='SUCCEEDED') completed_chunks,
                       (SELECT NVL(SUM(m.metric_value),0) FROM job_metric m
                         WHERE m.job_id=j.job_id AND m.metric_name='CHUNKS_TOTAL') total_chunks
                  FROM anonymization_job j
                  JOIN ruleset_version rv ON rv.ruleset_version_id=j.ruleset_version_id
                  JOIN ruleset r ON r.ruleset_id=rv.ruleset_id
                 WHERE j.job_reference = ?
                """;
        String stepSql = """
                SELECT s.step_number, s.step_type, s.status,
                       NVL((SELECT a.rows_processed
                              FROM job_step_attempt a
                             WHERE a.job_step_id = s.job_step_id
                               AND a.attempt_number = (SELECT MAX(x.attempt_number)
                                     FROM job_step_attempt x
                                    WHERE x.job_step_id = s.job_step_id)), 0) rows_processed
                  FROM job_step s
                 WHERE s.job_id = ?
                 ORDER BY s.step_number
                """;
        try (Connection connection = open();
                PreparedStatement jobStatement = connection.prepareStatement(jobSql)) {
            jobStatement.setString(1, reference);
            try (ResultSet row = jobStatement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException("Anonymization job was not found: " + reference);
                }
                JobSummary summary = summary(row);
                List<JobStepDetails> steps = loadSteps(connection, stepSql, summary.id());
                return new JobDetails(summary, row.getLong("ruleset_version_id"),
                        row.getLong("data_source_id"), row.getString("error_code"),
                        row.getString("error_message"), row.getString("pre_script_file_name"),
                        row.getString("post_script_file_name"), steps);
            }
        } catch (SQLException error) {
            throw failure("load job", error);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = com.privonym.repository.RepositoryConnections.open(jdbcUrl, username, new String(password));
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        return connection;
    }

    private String jobExecutionProjection() {
        return supportsExtendedJobConfiguration()
                ? "       j.parallel_degree, j.execution_mode, j.chunk_size, j.chunk_threshold,\n"
                : "       j.parallel_degree, 'AUTO' execution_mode, 100000 chunk_size, "
                        + "1000000 chunk_threshold,\n";
    }

    private boolean supportsExtendedJobConfiguration() {
        Boolean cached = extendedJobConfiguration;
        if (cached != null) return cached;
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*) FROM user_tab_columns
                         WHERE table_name='ANONYMIZATION_JOB'
                           AND column_name IN ('EXECUTION_MODE','CHUNK_SIZE','CHUNK_THRESHOLD')
                        """);
                ResultSet row = statement.executeQuery()) {
            row.next();
            boolean available = row.getInt(1) == 3;
            extendedJobConfiguration = available;
            return available;
        } catch (SQLException error) {
            throw failure("inspect Job execution configuration", error);
        }
    }

    private static List<JobStepDetails> loadSteps(Connection connection, String sql, long jobId)
            throws SQLException {
        List<JobStepDetails> steps = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, jobId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    steps.add(new JobStepDetails(row.getInt("step_number"),
                            row.getString("step_type"), row.getString("status"),
                            row.getLong("rows_processed")));
                }
            }
        }
        return steps;
    }

    private static JobSummary summary(ResultSet row) throws SQLException {
        String status = row.getString("status");
        long chunkRows = row.getLong("chunk_rows");
        long attemptRows = row.getLong("attempt_rows");
        long expectedRows = row.getLong("expected_rows");
        long processedRows = chunkRows > 0
                && (isActive(status) || "EXECUTION_FAILED".equals(status)
                        || "CANCELLED".equals(status))
                ? chunkRows : attemptRows;
        int progress = progress(status, processedRows, expectedRows);
        int completedChunks = row.getInt("completed_chunks");
        int totalChunks = Math.max(completedChunks, row.getInt("total_chunks"));
        return new JobSummary(row.getLong("job_id"), row.getString("job_reference"),
                status, row.getString("planned_at"), row.getString("started_at"),
                processedRows, expectedRows, row.getLong("ruleset_id"),
                row.getString("ruleset_name"), progress, row.getInt("parallel_degree"),
                row.getString("execution_mode"), row.getInt("chunk_size"),
                row.getLong("chunk_threshold"), completedChunks, totalChunks,
                row.getString("pre_script_file_name"), row.getString("post_script_file_name"));
    }

    private static boolean isActive(String status) {
        return List.of("RUNNING", "RETRYING", "PAUSING", "CANCELLING", "VALIDATING")
                .contains(status);
    }

    private static int progress(String status, long processed, long expected) {
        if ("SUCCEEDED".equals(status)) return 100;
        if ("VALIDATING".equals(status)) return 95;
        if (List.of("RUNNING", "RETRYING", "PAUSING", "CANCELLING").contains(status)) {
            return expected > 0
                    ? Math.min(94, (int) Math.floor(90.0 * processed / expected) + 5) : 5;
        }
        return switch (status) {
            case "PLANNING" -> 10;
            case "PLANNED" -> 20;
            case "PREFLIGHTING" -> 30;
            case "PREFLIGHT_PASSED", "APPROVED" -> 40;
            case "PLAN_FAILED" -> 10;
            case "PREFLIGHT_FAILED" -> 30;
            case "EXECUTION_FAILED" -> expected > 0
                    ? Math.min(94, (int) Math.floor(90.0 * processed / expected) + 5) : 5;
            case "CANCELLED" -> expected > 0
                    ? Math.min(94, (int) Math.floor(90.0 * processed / expected) + 5) : 0;
            case "VALIDATION_FAILED" -> 95;
            default -> 0;
        };
    }

    private static IllegalStateException failure(String action, SQLException error) {
        return new IllegalStateException("Unable to " + action + ": " + error.getMessage(), error);
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }
}
