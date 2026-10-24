package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.GeneratedOracleStatement;
import com.privonym.adapter.oracle.OracleUpdateGenerator;
import com.privonym.adapter.oracle.SqlChecksum;
import com.privonym.application.port.out.AuditEventSink;
import com.privonym.application.port.out.JobPlanRepository;
import com.privonym.application.port.out.JobRepository;
import com.privonym.application.port.out.PlanIntegrityChecker;
import com.privonym.application.port.out.SavedPlanLoader;
import com.privonym.application.port.out.ExecutionJournal;
import com.privonym.application.port.out.ValidationJournal;
import com.privonym.application.port.out.JobReportRepository;
import com.privonym.application.port.out.RulesetRepository;
import com.privonym.application.port.out.TargetDatabaseAdapter;
import com.privonym.application.service.ApplicationAuditEvent;
import com.privonym.application.service.GeneratedPlan;
import com.privonym.application.service.GeneratedPlanStep;
import com.privonym.application.service.JobPlanningRequest;
import com.privonym.application.service.ExecutablePlan;
import com.privonym.application.service.ExecutableStep;
import com.privonym.application.service.ExecutionOutcome;
import com.privonym.application.service.StepExecutionResult;
import com.privonym.application.service.ValidationMetric;
import com.privonym.application.service.ValidationOutcome;
import com.privonym.application.service.JobReport;
import com.privonym.domain.catalog.DataSourceId;
import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.JobId;
import com.privonym.domain.job.JobReference;
import com.privonym.domain.job.JobStatus;
import com.privonym.domain.job.PlanChecksum;
import com.privonym.domain.policy.RulesetVersionId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class OraclePlanningAdapter implements JobRepository, RulesetRepository,
        TargetDatabaseAdapter, JobPlanRepository, AuditEventSink, PlanIntegrityChecker,
        SavedPlanLoader, ExecutionJournal, ValidationJournal, JobReportRepository {
    private final Connection connection;
    private final OracleJobPlanRepository legacyPlans = new OracleJobPlanRepository();

    public OraclePlanningAdapter(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        try {
            OracleDiscoveryAdapter.ensurePostgresqlAlgorithmImplementations(connection);
            OracleDiscoveryAdapter.ensureSqlServerAlgorithmImplementations(connection);
            connection.commit();
        } catch (SQLException error) {
            throw repositoryFailure("publish target algorithm implementations", error);
        }
    }

    @Override
    public AnonymizationJob get(JobId id) {
        return load("WHERE j.job_id = ?", statement -> statement.setLong(1, id.value()));
    }

    public AnonymizationJob getByReference(String reference) {
        return load("WHERE j.job_reference = ?", statement -> statement.setString(1, reference));
    }

    public OracleConnectionDetails loadConnectionDetails(DataSourceId id) {
        String sql = """
                SELECT c.technology_code, c.host_name, c.port, c.service_name,c.database_name,
                       c.username_value, c.status connector_status, d.enabled
                  FROM data_source d JOIN connector c ON c.connector_id = d.connector_id
                 WHERE d.data_source_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException("Data source was not found: " + id.value());
                }
                if (!"Y".equals(row.getString("enabled"))
                        || !"ACTIVE".equals(row.getString("connector_status"))) {
                    throw new IllegalStateException("Target data source or connector is inactive");
                }
                return new OracleConnectionDetails(row.getString("technology_code"),
                        row.getString("host_name"), row.getInt("port"),
                        row.getString("service_name"), row.getString("database_name"),
                        row.getString("username_value"));
            }
        } catch (SQLException error) {
            throw repositoryFailure("load target connection", error);
        }
    }

    public char[] loadSavedTargetPassword(DataSourceId id) {
        String sql = """
                SELECT c.encrypted_password
                  FROM data_source d JOIN connector c ON c.connector_id=d.connector_id
                 WHERE d.data_source_id=?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getString(1) == null || row.getString(1).isBlank()) {
                    throw new IllegalStateException("Saved target connection credentials are missing");
                }
                return LocalCredentialStore.decrypt(row.getString(1));
            }
        } catch (SQLException error) {
            throw repositoryFailure("load saved target password", error);
        }
    }

    @Override
    public List<String> verify(JobId jobId) {
        String sql = """
                SELECT step_number, generated_code, generated_code_checksum
                  FROM job_step
                 WHERE job_id = ? AND step_type = 'ANONYMIZE_TABLE'
                 ORDER BY step_number
                """;
        List<String> messages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, jobId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int number = rows.getInt("step_number");
                    String code = ProtectedSql.read(rows.getString("generated_code"));
                    String stored = rows.getString("generated_code_checksum");
                    if (code == null || stored == null
                            || !SqlChecksum.sha256(code).equals(stored)) {
                        throw new IllegalStateException(
                                "Generated code checksum mismatch at step " + number);
                    }
                    messages.add("Step " + number + ": generated SQL checksum verified");
                }
            }
        } catch (SQLException error) {
            throw repositoryFailure("verify generated plan", error);
        }
        if (messages.isEmpty()) {
            throw new IllegalStateException("No generated anonymization step was found");
        }
        return List.copyOf(messages);
    }

    @Override
    public ExecutablePlan loadExecutablePlan(JobId jobId) {
        String sql = """
                SELECT job_step_id, step_number, generated_code, generated_code_checksum
                  FROM job_step
                 WHERE job_id = ? AND step_type = 'ANONYMIZE_TABLE'
                   AND status IN ('READY','FAILED')
                 ORDER BY step_number
                """;
        List<ExecutableStep> steps = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, jobId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String code = ProtectedSql.read(rows.getString("generated_code"));
                    String stored = rows.getString("generated_code_checksum");
                    if (code == null || stored == null || !SqlChecksum.sha256(code).equals(stored)) {
                        throw new IllegalStateException("Saved SQL checksum mismatch at step "
                                + rows.getInt("step_number"));
                    }
                    steps.add(new ExecutableStep(rows.getLong("job_step_id"),
                            rows.getInt("step_number"), code, new PlanChecksum(stored)));
                }
            }
        } catch (SQLException error) {
            throw repositoryFailure("load executable plan", error);
        }
        if (steps.isEmpty()) {
            throw new IllegalStateException("No READY anonymization step was found");
        }
        String fingerprint = String.join("\n",
                steps.stream().map(step -> step.checksum().value()).toList());
        return new ExecutablePlan(jobId,
                new PlanChecksum(SqlChecksum.sha256(fingerprint)), steps);
    }

    @Override
    public void start(AnonymizationJob runningJob, ExecutablePlan plan,
            ApplicationAuditEvent event) {
        try {
            String claim = """
                    UPDATE anonymization_job
                       SET status = 'RUNNING', started_at = COALESCE(started_at, SYSTIMESTAMP),
                           heartbeat_at = SYSTIMESTAMP, error_code = NULL, error_message = NULL
                     WHERE job_id = ? AND status IN ('PREFLIGHT_PASSED','EXECUTION_FAILED')
                    """;
            try (PreparedStatement statement = connection.prepareStatement(claim)) {
                statement.setLong(1, runningJob.id().value());
                requireOne(statement.executeUpdate(), "claim job for execution");
            }
            try (PreparedStatement precheck = connection.prepareStatement("""
                    UPDATE job_step SET status = 'SUCCEEDED',
                           started_at = COALESCE(started_at, SYSTIMESTAMP), ended_at = SYSTIMESTAMP
                     WHERE job_id = ? AND step_type = 'PRECHECK' AND status = 'READY'
                    """)) {
                precheck.setLong(1, runningJob.id().value());
                precheck.executeUpdate();
            }
            for (ExecutableStep step : plan.steps()) {
                markStepStarted(step);
            }
            append(event);
            connection.commit();
        } catch (SQLException | RuntimeException error) {
            rollbackQuietly();
            throw executionJournalFailure("start execution", error);
        }
    }

    @Override
    public void succeed(AnonymizationJob validatingJob, ExecutionOutcome outcome,
            ApplicationAuditEvent event) {
        try {
            for (StepExecutionResult result : outcome.steps()) {
                finishStep(result.jobStepId(), "SUCCEEDED", result.rowsProcessed(), null);
            }
            updateExecutionJob(validatingJob.id(), "VALIDATING", null);
            append(event);
            connection.commit();
        } catch (SQLException | RuntimeException error) {
            rollbackQuietly();
            throw executionJournalFailure("complete execution", error);
        }
    }

    @Override
    public void fail(AnonymizationJob failedJob, ExecutablePlan plan, RuntimeException error,
            ApplicationAuditEvent event) {
        try {
            for (ExecutableStep step : plan.steps()) {
                long committedRows = 0;
                try (PreparedStatement chunks = connection.prepareStatement(
                        "SELECT COALESCE(SUM(rows_processed),0) FROM job_chunk "
                        + "WHERE job_step_id=? AND status='SUCCEEDED'")) {
                    chunks.setLong(1, step.jobStepId());
                    try (ResultSet row = chunks.executeQuery()) {
                        row.next(); committedRows = row.getLong(1);
                    }
                }
                finishStep(step.jobStepId(), "FAILED", committedRows, error);
            }
            updateExecutionJob(failedJob.id(), "EXECUTION_FAILED", error);
            append(event);
            connection.commit();
        } catch (SQLException | RuntimeException journalError) {
            rollbackQuietly();
            error.addSuppressed(journalError);
        }
    }

    @Override
    public void cancel(AnonymizationJob cancelledJob, ExecutablePlan plan,
            ApplicationAuditEvent event) {
        try {
            try (PreparedStatement attempts = connection.prepareStatement("""
                    UPDATE job_step_attempt
                       SET status='CANCELLED',ended_at=SYSTIMESTAMP,
                           heartbeat_at=SYSTIMESTAMP,error_code=NULL,error_message=NULL
                     WHERE job_step_id IN
                           (SELECT job_step_id FROM job_step WHERE job_id=?)
                       AND status='RUNNING'
                    """)) {
                attempts.setLong(1, cancelledJob.id().value());
                attempts.executeUpdate();
            }
            try (PreparedStatement steps = connection.prepareStatement("""
                    UPDATE job_step SET status='CANCELLED',ended_at=SYSTIMESTAMP
                     WHERE job_id=? AND status='RUNNING'
                    """)) {
                steps.setLong(1, cancelledJob.id().value());
                steps.executeUpdate();
            }
            try (PreparedStatement chunks = connection.prepareStatement("""
                    UPDATE job_chunk SET status='CANCELLED',ended_at=SYSTIMESTAMP,
                           error_message='Cancelled by operator'
                     WHERE job_step_id IN
                           (SELECT job_step_id FROM job_step WHERE job_id=?)
                       AND status='RUNNING'
                    """)) {
                chunks.setLong(1, cancelledJob.id().value());
                chunks.executeUpdate();
            }
            try (PreparedStatement job = connection.prepareStatement("""
                    UPDATE anonymization_job
                       SET status='CANCELLED',ended_at=SYSTIMESTAMP,
                           heartbeat_at=SYSTIMESTAMP,error_code=NULL,error_message=NULL
                     WHERE job_id=?
                       AND status IN ('RUNNING','RETRYING','PAUSING','CANCELLING')
                    """)) {
                job.setLong(1, cancelledJob.id().value());
                requireOne(job.executeUpdate(), "cancel job execution");
            }
            append(event);
            connection.commit();
        } catch (SQLException | RuntimeException error) {
            rollbackQuietly();
            throw executionJournalFailure("cancel execution", error);
        }
    }

    @Override
    public void succeed(AnonymizationJob validatingJob, ValidationOutcome outcome,
            ApplicationAuditEvent event) {
        try {
            finishLifecycleStep(validatingJob.id(), "VALIDATE_DATA", "SUCCEEDED");
            saveMetrics(validatingJob.id(), outcome.metrics());
            saveValidationResults(validatingJob.id(), outcome);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE anonymization_job
                       SET status='VALIDATING', ended_at=NULL,
                           error_code=NULL, error_message=NULL, heartbeat_at=SYSTIMESTAMP
                     WHERE job_id=? AND status IN ('VALIDATING','VALIDATION_FAILED')
                    """)) {
                statement.setLong(1, validatingJob.id().value());
                requireOne(statement.executeUpdate(), "restore validated job status");
            }
            append(event);
            connection.commit();
        } catch (SQLException | RuntimeException error) {
            rollbackQuietly();
            throw executionJournalFailure("save validation result", error);
        }
    }

    @Override
    public void fail(AnonymizationJob failedJob, RuntimeException error,
            ApplicationAuditEvent event) {
        try {
            finishLifecycleStep(failedJob.id(), "VALIDATE_DATA", "FAILED");
            saveFailedValidationResult(failedJob.id(), error);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE anonymization_job SET status = 'VALIDATION_FAILED',
                           ended_at = SYSTIMESTAMP, error_code = ?, error_message = ?
                     WHERE job_id = ? AND status IN ('VALIDATING','VALIDATION_FAILED')
                    """)) {
                setError(statement, 1, 2, error);
                statement.setLong(3, failedJob.id().value());
                requireOne(statement.executeUpdate(), "fail job validation");
            }
            append(event);
            connection.commit();
        } catch (SQLException | RuntimeException journalError) {
            rollbackQuietly();
            error.addSuppressed(journalError);
        }
    }

    @Override
    public JobReport load(JobId jobId) {
        String summarySql = """
                SELECT j.job_reference,j.started_at,NVL(j.ended_at,SYSTIMESTAMP) report_end_at,
                       (SELECT COUNT(*) FROM job_step s
                         WHERE s.job_id = j.job_id AND s.status = 'SUCCEEDED') succeeded_steps,
                       (SELECT COALESCE(SUM(a.rows_processed), 0)
                          FROM job_step s JOIN job_step_attempt a
                            ON a.job_step_id = s.job_step_id
                         WHERE s.job_id = j.job_id AND a.status = 'SUCCEEDED'
                           AND a.attempt_number = (SELECT MAX(x.attempt_number)
                                 FROM job_step_attempt x
                                WHERE x.job_step_id = s.job_step_id)) rows_processed
                  FROM anonymization_job j
                 WHERE j.job_id = ?
                   AND EXISTS (SELECT 1 FROM job_step v WHERE v.job_id = j.job_id
                               AND v.step_type = 'VALIDATE_DATA' AND v.status = 'SUCCEEDED')
                """;
        try (PreparedStatement statement = connection.prepareStatement(summarySql)) {
            statement.setLong(1, jobId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("Successful validation is required for report");
                }
                java.sql.Timestamp started=row.getTimestamp("started_at"),ended=row.getTimestamp("report_end_at");
                long duration=started==null||ended==null?0:Math.max(0,java.time.Duration.between(started.toInstant(),ended.toInstant()).getSeconds());
                return new JobReport(jobId, new JobReference(row.getString("job_reference")),
                        row.getLong("rows_processed"), row.getInt("succeeded_steps"),
                        loadMetrics(jobId),duration);
            }
        } catch (SQLException error) {
            throw repositoryFailure("load final report", error);
        }
    }

    @Override
    public void complete(AnonymizationJob succeededJob, JobReport report,
            ApplicationAuditEvent event) {
        try {
            finishLifecycleStep(succeededJob.id(), "GENERATE_REPORT", "SUCCEEDED");
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE anonymization_job SET status = 'SUCCEEDED', ended_at = SYSTIMESTAMP,
                           heartbeat_at = SYSTIMESTAMP, error_code = NULL, error_message = NULL
                     WHERE job_id = ? AND status = 'VALIDATING'
                    """)) {
                statement.setLong(1, succeededJob.id().value());
                requireOne(statement.executeUpdate(), "complete anonymization job");
            }
            append(event);
            connection.commit();
        } catch (SQLException | RuntimeException error) {
            rollbackQuietly();
            throw executionJournalFailure("complete final report", error);
        }
    }

    private void finishLifecycleStep(JobId jobId, String type, String status)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE job_step SET status = ?, started_at = COALESCE(started_at, SYSTIMESTAMP),
                       ended_at = SYSTIMESTAMP WHERE job_id = ? AND step_type = ?
                """)) {
            statement.setString(1, status);
            statement.setLong(2, jobId.value());
            statement.setString(3, type);
            if (statement.executeUpdate() == 0) {
                int stepNumber;
                try (PreparedStatement next = connection.prepareStatement("""
                        SELECT NVL(MAX(step_number),0)+10
                          FROM job_step WHERE job_id=?
                        """)) {
                    next.setLong(1, jobId.value());
                    try (ResultSet row = next.executeQuery()) {
                        row.next();
                        stepNumber = row.getInt(1);
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO job_step(job_id,step_number,step_type,status,started_at,ended_at)
                        VALUES (?,?,?,?,SYSTIMESTAMP,SYSTIMESTAMP)
                        """)) {
                    insert.setLong(1, jobId.value());
                    insert.setInt(2, stepNumber);
                    insert.setString(3, type);
                    insert.setString(4, status);
                    requireOne(insert.executeUpdate(), "insert " + type + " step");
                }
            }
        }
    }

    private void saveMetrics(JobId jobId, List<ValidationMetric> metrics)
            throws SQLException {
        for (ValidationMetric metric : metrics) {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM job_metric WHERE job_id = ? AND job_step_id IS NULL
                      AND metric_name = ?
                    """)) {
                delete.setLong(1, jobId.value());
                delete.setString(2, metric.name());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO job_metric (job_id, metric_name, metric_value)
                    VALUES (?, ?, ?)
                    """)) {
                insert.setLong(1, jobId.value());
                insert.setString(2, metric.name());
                insert.setLong(3, metric.value());
                insert.executeUpdate();
            }
        }
    }

    private void saveValidationResults(JobId jobId, ValidationOutcome outcome)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM validation_result WHERE job_id = ?")) {
            delete.setLong(1, jobId.value());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO validation_result
                    (job_id, validation_type, status, message)
                VALUES (?, 'ROW_COUNT', 'PASSED', ?)
                """)) {
            for (String message : outcome.messages()) {
                insert.setLong(1, jobId.value());
                insert.setString(2, message);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void saveFailedValidationResult(JobId jobId, RuntimeException error)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO validation_result
                    (job_id, validation_type, status, message)
                VALUES (?, 'CUSTOM', 'FAILED', ?)
                """)) {
            insert.setLong(1, jobId.value());
            String message = String.valueOf(error.getMessage());
            insert.setString(2, message.substring(0, Math.min(message.length(), 4000)));
            insert.executeUpdate();
        }
    }

    private List<ValidationMetric> loadMetrics(JobId jobId) throws SQLException {
        List<ValidationMetric> metrics = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT metric_name, metric_value FROM job_metric
                 WHERE job_id = ? AND job_step_id IS NULL ORDER BY metric_name
                """)) {
            statement.setLong(1, jobId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    metrics.add(new ValidationMetric(
                            rows.getString("metric_name"), rows.getLong("metric_value")));
                }
            }
        }
        return List.copyOf(metrics);
    }

    private void markStepStarted(ExecutableStep step) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE job_step SET status = 'RUNNING', started_at = SYSTIMESTAMP,
                       ended_at = NULL WHERE job_step_id = ? AND status IN ('READY','FAILED')
                """)) {
            update.setLong(1, step.jobStepId());
            requireOne(update.executeUpdate(), "start execution step");
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO job_step_attempt
                    (job_step_id, attempt_number, status, worker_name)
                VALUES (?,
                        (SELECT COALESCE(MAX(attempt_number), 0) + 1
                           FROM job_step_attempt WHERE job_step_id = ?),
                        'RUNNING', ?)
                """)) {
            insert.setLong(1, step.jobStepId());
            insert.setLong(2, step.jobStepId());
            insert.setString(3, workerName());
            requireOne(insert.executeUpdate(), "create step attempt");
        }
    }

    private void finishStep(long stepId, String status, long rows, RuntimeException error)
            throws SQLException {
        try (PreparedStatement attempt = connection.prepareStatement("""
                UPDATE job_step_attempt
                   SET status = ?, ended_at = SYSTIMESTAMP, heartbeat_at = SYSTIMESTAMP,
                       rows_processed = ?, error_code = ?, error_message = ?
                 WHERE job_step_id = ? AND status = 'RUNNING'
                """)) {
            attempt.setString(1, status);
            attempt.setLong(2, rows);
            setError(attempt, 3, 4, error);
            attempt.setLong(5, stepId);
            requireOne(attempt.executeUpdate(), "finish step attempt");
        }
        try (PreparedStatement step = connection.prepareStatement("""
                UPDATE job_step SET status = ?, ended_at = SYSTIMESTAMP
                 WHERE job_step_id = ? AND status = 'RUNNING'
                """)) {
            step.setString(1, status);
            step.setLong(2, stepId);
            requireOne(step.executeUpdate(), "finish execution step");
        }
    }

    private void updateExecutionJob(JobId id, String status, RuntimeException error)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE anonymization_job SET status = ?, heartbeat_at = SYSTIMESTAMP,
                       error_code = ?, error_message = ? WHERE job_id = ? AND status = 'RUNNING'
                """)) {
            statement.setString(1, status);
            setError(statement, 2, 3, error);
            statement.setLong(4, id.value());
            requireOne(statement.executeUpdate(), "finish job execution");
        }
    }

    private static void setError(PreparedStatement statement, int codeIndex,
            int messageIndex, RuntimeException error) throws SQLException {
        if (error == null) {
            statement.setNull(codeIndex, Types.VARCHAR);
            statement.setNull(messageIndex, Types.VARCHAR);
        } else {
            statement.setString(codeIndex, error.getClass().getSimpleName());
            String message = String.valueOf(error.getMessage());
            statement.setString(messageIndex,
                    message.substring(0, Math.min(message.length(), 4000)));
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original journal failure.
        }
    }

    private static String workerName() {
        String name = System.getenv("COMPUTERNAME");
        return name == null || name.isBlank() ? "PRIVONYM-CLI" : name;
    }

    private static IllegalStateException executionJournalFailure(
            String operation, Exception error) {
        return new IllegalStateException("Unable to " + operation + ": "
                + error.getMessage(), error);
    }

    private AnonymizationJob load(String predicate, Binder binder) {
        String sql = """
                SELECT j.job_id, j.job_reference, j.ruleset_version_id,
                       j.data_source_id, j.status
                  FROM anonymization_job j
                """ + predicate;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException("Anonymization job was not found");
                }
                JobStatus status = fromOracleStatus(row.getString("status"));
                PlanChecksum checksum = requiresChecksum(status)
                        ? loadPlanChecksum(row.getLong("job_id")) : null;
                return AnonymizationJob.restore(
                        new JobId(row.getLong("job_id")),
                        new JobReference(row.getString("job_reference")),
                        new RulesetVersionId(row.getLong("ruleset_version_id")),
                        new DataSourceId(row.getLong("data_source_id")), status, checksum);
            }
        } catch (SQLException error) {
            throw repositoryFailure("load job", error);
        }
    }

    @Override
    public void save(AnonymizationJob job) {
        String sql = """
                UPDATE anonymization_job
                   SET status = ?,
                       planned_at = CASE WHEN ? = 'READY' THEN SYSTIMESTAMP ELSE planned_at END,
                       error_code = CASE WHEN ? = 'FAILED' THEN 'PLAN_FAILED' ELSE NULL END,
                       error_message = CASE WHEN ? = 'FAILED' THEN 'Plan generation failed' ELSE NULL END
                 WHERE job_id = ?
                """;
        String status = toOracleStatus(job.status());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, status);
            statement.setString(3, status);
            statement.setString(4, status);
            statement.setLong(5, job.id().value());
            requireOne(statement.executeUpdate(), "save job");
        } catch (SQLException error) {
            throw repositoryFailure("save job", error);
        }
    }

    @Override
    public void requirePublished(RulesetVersionId id) {
        String sql = "SELECT status FROM ruleset_version WHERE ruleset_version_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !"PUBLISHED".equals(row.getString(1))) {
                    throw new IllegalStateException("Ruleset version is not published: " + id.value());
                }
            }
        } catch (SQLException error) {
            throw repositoryFailure("validate ruleset", error);
        }
    }

    @Override
    public GeneratedPlan generatePlan(JobPlanningRequest request) {
        try {
            List<GeneratedPlanStep> steps = new ArrayList<>();
            String technology = loadConnectionDetails(request.dataSourceId()).technologyCode();
            for (RepositoryTablePlan table : legacyPlans.load(
                    connection, request.jobReference().value(), technology)) {
                GeneratedOracleStatement statement;
                if (!table.tableAlgorithmCode().isBlank()) {
                    String sql = switch (table.tableAlgorithmCode()) {
                        case "DELETE_TABLE_ROWS" -> "DELETE FROM " + table.table().toSql()
                                + (table.filterExpression().isBlank() ? ""
                                        : " WHERE " + table.filterExpression());
                        case "TRUNCATE_TABLE" -> {
                            if (!table.filterExpression().isBlank()) {
                                throw new IllegalStateException(
                                        "TRUNCATE_TABLE cannot have a row criterion");
                            }
                            yield "TRUNCATE TABLE " + table.table().toSql();
                        }
                        default -> throw new IllegalStateException(
                                "Unsupported table algorithm: " + table.tableAlgorithmCode());
                    };
                    statement = new GeneratedOracleStatement(sql, SqlChecksum.sha256(sql));
                } else {
                    statement = generateColumnStatement(table);
                }
                steps.add(new GeneratedPlanStep(table.catalogTableId(), table.table().toSql(),
                        statement.sql(), new PlanChecksum(statement.sha256())));
            }
            String fingerprint = String.join("\n",
                    steps.stream().map(step -> step.checksum().value()).toList());
            return new GeneratedPlan(new PlanChecksum(SqlChecksum.sha256(fingerprint)), steps);
        } catch (SQLException error) {
            throw repositoryFailure("generate plan", error);
        }
    }

    static GeneratedOracleStatement generateColumnStatement(RepositoryTablePlan table) {
        GeneratedOracleStatement generated = new OracleUpdateGenerator().generate(
                table.table(), table.assignments());
        if (table.filterExpression().isBlank()) return generated;
        String sql = generated.sql() + "\nWHERE " + table.filterExpression();
        return new GeneratedOracleStatement(sql, SqlChecksum.sha256(sql));
    }

    @Override
    public void save(JobId jobId, GeneratedPlan plan) {
        String updateSql = """
                UPDATE job_step
                   SET generated_code = ?, generated_code_checksum = ?, status = 'READY'
                 WHERE job_id = ? AND catalog_table_id = ?
                   AND step_type = 'ANONYMIZE_TABLE'
                """;
        String insertSql = """
                INSERT INTO job_step(job_id,step_number,step_type,catalog_table_id,status,
                                     generated_code,generated_code_checksum)
                VALUES(?,?,'ANONYMIZE_TABLE',?,'READY',?,?)
                """;
        int nextStepNumber = 10;
        try (PreparedStatement next = connection.prepareStatement(
                "SELECT NVL(MAX(step_number),0)+10 FROM job_step WHERE job_id=?")) {
            next.setLong(1, jobId.value());
            try (ResultSet row = next.executeQuery()) { row.next(); nextStepNumber = row.getInt(1); }
        } catch (SQLException error) {
            throw repositoryFailure("allocate generated plan steps", error);
        }
        try (PreparedStatement statement = connection.prepareStatement(updateSql);
                PreparedStatement insert = connection.prepareStatement(insertSql)) {
            int stepNumber = nextStepNumber;
            for (GeneratedPlanStep step : plan.steps()) {
                String storedCode = ProtectedSql.store(step.generatedCode());
                statement.setString(1, storedCode);
                statement.setString(2, step.checksum().value());
                statement.setLong(3, jobId.value());
                statement.setLong(4, step.catalogTableId());
                if (statement.executeUpdate() == 0) {
                    insert.setLong(1, jobId.value());
                    insert.setInt(2, stepNumber);
                    insert.setLong(3, step.catalogTableId());
                    insert.setString(4, storedCode);
                    insert.setString(5, step.checksum().value());
                    requireOne(insert.executeUpdate(), "insert generated plan step");
                }
                stepNumber += 10;
            }
            rebuildStepDependencies(jobId.value());
        } catch (SQLException error) {
            throw repositoryFailure("save generated plan", error);
        }
    }

    private void rebuildStepDependencies(long jobId) throws SQLException {
        Map<Long, DependencyStep> byTable = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT job_step_id,catalog_table_id,generated_code
                  FROM job_step
                 WHERE job_id=? AND step_type='ANONYMIZE_TABLE'
                """)) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    DependencyStep step = new DependencyStep(rows.getLong(1), rows.getLong(2),
                            operationType(ProtectedSql.read(rows.getString(3))));
                    byTable.put(step.tableId(), step);
                }
            }
        }
        rejectMaskedRelationshipColumns(jobId);
        Set<DependencyEdge> edges = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT cr.parent_table_id,cr.child_table_id,cr.constraint_name
                  FROM catalog_relationship cr
                  JOIN catalog_table child ON child.catalog_table_id=cr.child_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id=child.catalog_schema_id
                  JOIN anonymization_job j ON j.data_source_id=cs.data_source_id
                 WHERE j.job_id=?
                """)) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    DependencyStep parent = byTable.get(rows.getLong(1));
                    DependencyStep child = byTable.get(rows.getLong(2));
                    if (parent == null || child == null || parent.stepId() == child.stepId()) continue;
                    if (isStructural(parent.operation()) || isStructural(child.operation())) {
                        edges.add(new DependencyEdge(parent.stepId(), child.stepId(),
                                rows.getString(3)));
                    }
                }
            }
        }
        Map<Long,Integer> levels = dependencyLevels(byTable, edges);
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM job_step_dependency WHERE job_step_id IN (SELECT job_step_id FROM job_step WHERE job_id=?)")) {
            delete.setLong(1, jobId); delete.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE job_step SET dependency_group=? WHERE job_step_id=?");
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO job_step_dependency(job_step_id,depends_on_step_id) VALUES(?,?)")) {
            for (DependencyStep step : byTable.values()) {
                update.setInt(1, levels.getOrDefault(step.stepId(), 0));
                update.setLong(2, step.stepId()); update.addBatch();
            }
            update.executeBatch();
            for (DependencyEdge edge : edges) {
                insert.setLong(1, edge.dependentStepId());
                insert.setLong(2, edge.prerequisiteStepId()); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void rejectMaskedRelationshipColumns(long jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT cr.constraint_name,pcs.schema_name parent_schema,pt.table_name parent_table,
                       pc.column_name parent_column,ccs.schema_name child_schema,
                       ct.table_name child_table,cc.column_name child_column,
                       pmr.rule_id parent_rule_id,cmr.rule_id child_rule_id,
                       pa.code parent_algorithm,ca.code child_algorithm,
                       pc.data_length parent_length,cc.data_length child_length
                  FROM anonymization_job j
                  JOIN catalog_relationship cr ON 1=1
                  JOIN catalog_relationship_column crc ON crc.relationship_id=cr.relationship_id
                  JOIN catalog_column pc ON pc.catalog_column_id=crc.parent_column_id
                  JOIN catalog_table pt ON pt.catalog_table_id=pc.catalog_table_id
                  JOIN catalog_schema pcs ON pcs.catalog_schema_id=pt.catalog_schema_id
                  JOIN catalog_column cc ON cc.catalog_column_id=crc.child_column_id
                  JOIN catalog_table ct ON ct.catalog_table_id=cc.catalog_table_id
                  JOIN catalog_schema ccs ON ccs.catalog_schema_id=ct.catalog_schema_id
                  LEFT JOIN masking_rule pmr ON pmr.ruleset_version_id=j.ruleset_version_id
                       AND pmr.catalog_column_id=pc.catalog_column_id AND pmr.enabled='Y'
                  LEFT JOIN masking_rule cmr ON cmr.ruleset_version_id=j.ruleset_version_id
                       AND cmr.catalog_column_id=cc.catalog_column_id AND cmr.enabled='Y'
                  LEFT JOIN algorithm_version pav ON pav.algorithm_version_id=pmr.algorithm_version_id
                  LEFT JOIN algorithm pa ON pa.algorithm_id=pav.algorithm_id
                  LEFT JOIN algorithm_version cav ON cav.algorithm_version_id=cmr.algorithm_version_id
                  LEFT JOIN algorithm ca ON ca.algorithm_id=cav.algorithm_id
                 WHERE j.job_id=? AND pcs.data_source_id=j.data_source_id
                   AND (pmr.rule_id IS NOT NULL OR cmr.rule_id IS NOT NULL)
                """)) {
            statement.setLong(1, jobId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    long parentRule = row.getLong("parent_rule_id");
                    boolean parentMissing = row.wasNull();
                    long childRule = row.getLong("child_rule_id");
                    boolean childMissing = row.wasNull();
                    boolean pairedToken = !parentMissing && !childMissing
                            && "RELATIONSHIP_AWARE_TOKEN".equals(row.getString("parent_algorithm"))
                            && "RELATIONSHIP_AWARE_TOKEN".equals(row.getString("child_algorithm"))
                            && compatibleTokenLength(row.getObject("parent_length",Long.class),
                                    row.getObject("child_length",Long.class))
                            && ruleParameters(parentRule).equals(ruleParameters(childRule));
                    if (!pairedToken) throw new IllegalStateException(
                            "Inconsistent masking of foreign-key relationship ("
                                    + row.getString("constraint_name") + "): "
                                    + row.getString("parent_schema") + "."
                                    + row.getString("parent_table") + "."
                                    + row.getString("parent_column") + " -> "
                                    + row.getString("child_schema") + "."
                                    + row.getString("child_table") + "."
                                    + row.getString("child_column")
                                    + ". Apply RELATIONSHIP_AWARE_TOKEN to both columns with the "
                                    + "same key_material, token_namespace and compatible lengths.");
                }
            }
        }
    }

    private Map<String,String> ruleParameters(long ruleId) throws SQLException {
        Map<String,String> values = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT apd.parameter_name,NVL(rp.parameter_value,apd.default_value)
                  FROM masking_rule mr
                  JOIN algorithm_parameter_def apd
                    ON apd.algorithm_version_id=mr.algorithm_version_id
                  LEFT JOIN rule_parameter rp ON rp.rule_id=mr.rule_id
                    AND rp.parameter_definition_id=apd.parameter_definition_id
                 WHERE mr.rule_id=? ORDER BY apd.parameter_name
                """)) {
            statement.setLong(1, ruleId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) values.put(rows.getString(1),rows.getString(2));
            }
        }
        return values;
    }

    private static boolean compatibleTokenLength(Long parent,Long child) {
        if (parent == null || child == null) return false;
        return parent.equals(child) || parent >= 64 && child >= 64;
    }

    private static Map<Long,Integer> dependencyLevels(Map<Long,DependencyStep> steps,
            Set<DependencyEdge> edges) {
        Map<Long,Integer> indegree = new LinkedHashMap<>();
        Map<Long,List<Long>> outgoing = new LinkedHashMap<>();
        Map<Long,Integer> levels = new LinkedHashMap<>();
        for (DependencyStep step : steps.values()) {
            indegree.put(step.stepId(), 0); levels.put(step.stepId(), 0);
        }
        for (DependencyEdge edge : edges) {
            indegree.compute(edge.dependentStepId(), (ignored,value) -> value == null ? 1 : value + 1);
            outgoing.computeIfAbsent(edge.prerequisiteStepId(), ignored -> new ArrayList<>())
                    .add(edge.dependentStepId());
        }
        ArrayDeque<Long> ready = new ArrayDeque<>();
        indegree.forEach((step,count) -> { if (count == 0) ready.add(step); });
        int visited = 0;
        while (!ready.isEmpty()) {
            long prerequisite = ready.remove(); visited++;
            for (long dependent : outgoing.getOrDefault(prerequisite, List.of())) {
                levels.put(dependent, Math.max(levels.get(dependent), levels.get(prerequisite) + 1));
                int remaining = indegree.compute(dependent, (ignored,value) -> value - 1);
                if (remaining == 0) ready.add(dependent);
            }
        }
        if (visited != steps.size()) {
            String constraints = edges.stream().map(DependencyEdge::constraintName).distinct()
                    .sorted().reduce((left,right) -> left + ", " + right).orElse("unknown");
            throw new IllegalStateException("Cyclic structural table dependencies detected: "
                    + constraints + ". DELETE/TRUNCATE cannot be safely ordered.");
        }
        return levels;
    }

    private static String operationType(String generatedCode) {
        if (generatedCode == null) return "UPDATE";
        String normalized = generatedCode.stripLeading().toUpperCase();
        if (normalized.startsWith("DELETE ")) return "DELETE";
        if (normalized.startsWith("TRUNCATE ")) return "TRUNCATE";
        return "UPDATE";
    }

    private static boolean isStructural(String operation) {
        return "DELETE".equals(operation) || "TRUNCATE".equals(operation);
    }

    private record DependencyStep(long stepId,long tableId,String operation) { }
    private record DependencyEdge(long dependentStepId,long prerequisiteStepId,
            String constraintName) { }

    @Override
    public void append(ApplicationAuditEvent event) {
        String sql = """
                INSERT INTO job_event
                    (job_id, event_timestamp, severity, event_code, message, details_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, event.jobId().value());
            statement.setTimestamp(2, Timestamp.from(event.occurredAt()));
            statement.setString(3, event.eventType().endsWith("FAILED") ? "ERROR" : "INFO");
            statement.setString(4, event.eventType());
            statement.setString(5, event.detail());
            statement.setNull(6, Types.CLOB);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw repositoryFailure("append job event", error);
        }
    }

    private PlanChecksum loadPlanChecksum(long jobId) throws SQLException {
        String sql = """
                SELECT generated_code_checksum FROM job_step
                 WHERE job_id = ? AND generated_code_checksum IS NOT NULL
                 ORDER BY step_number
                """;
        List<String> checksums = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    checksums.add(rows.getString(1));
                }
            }
        }
        if (checksums.isEmpty()) {
            throw new IllegalStateException("Planned job has no generated plan checksum");
        }
        return new PlanChecksum(SqlChecksum.sha256(String.join("\n", checksums)));
    }

    private static JobStatus fromOracleStatus(String status) {
        return switch (status) {
            case "CREATED" -> JobStatus.DRAFT;
            case "DRAFT" -> JobStatus.DRAFT;
            case "PLANNING" -> JobStatus.PLANNING;
            case "PLAN_FAILED" -> JobStatus.PLAN_FAILED;
            case "READY" -> JobStatus.PLANNED;
            case "PLANNED" -> JobStatus.PLANNED;
            case "PREFLIGHTING" -> JobStatus.PREFLIGHTING;
            case "PREFLIGHT_PASSED" -> JobStatus.PREFLIGHT_PASSED;
            case "PREFLIGHT_FAILED" -> JobStatus.PREFLIGHT_FAILED;
            case "APPROVED" -> JobStatus.APPROVED;
            case "EXECUTION_FAILED" -> JobStatus.EXECUTION_FAILED;
            case "RUNNING" -> JobStatus.RUNNING;
            case "PAUSING" -> JobStatus.PAUSING;
            case "PAUSED" -> JobStatus.PAUSED;
            case "RETRYING" -> JobStatus.RETRYING;
            case "VALIDATING" -> JobStatus.VALIDATING;
            case "VALIDATION_FAILED" -> JobStatus.VALIDATION_FAILED;
            case "SUCCEEDED" -> JobStatus.SUCCEEDED;
            case "CANCELLED", "CANCELLING" -> JobStatus.CANCELLED;
            case "FAILED" -> JobStatus.PLAN_FAILED;
            default -> throw new IllegalStateException("Unsupported Oracle job status: " + status);
        };
    }

    private static String toOracleStatus(JobStatus status) {
        return switch (status) {
            case DRAFT -> "CREATED";
            case PLANNING -> "PLANNING";
            case PLANNED -> "READY";
            case PLAN_FAILED -> "FAILED";
            case PREFLIGHTING -> "PREFLIGHTING";
            case PREFLIGHT_PASSED -> "PREFLIGHT_PASSED";
            case PREFLIGHT_FAILED -> "PREFLIGHT_FAILED";
            case APPROVED -> "APPROVED";
            case RUNNING -> "RUNNING";
            case PAUSING -> "PAUSING";
            case PAUSED -> "PAUSED";
            case EXECUTION_FAILED -> "EXECUTION_FAILED";
            case RETRYING -> "RETRYING";
            case VALIDATING -> "VALIDATING";
            case VALIDATION_FAILED -> "VALIDATION_FAILED";
            case SUCCEEDED -> "SUCCEEDED";
            case CANCELLED -> "CANCELLED";
            default -> throw new IllegalStateException(
                    "Planning adapter cannot persist job status: " + status);
        };
    }

    private static boolean requiresChecksum(JobStatus status) {
        return switch (status) {
            case PLANNED, PREFLIGHTING, PREFLIGHT_PASSED, PREFLIGHT_FAILED,
                 APPROVED, RUNNING, PAUSING, PAUSED, EXECUTION_FAILED,
                 RETRYING, VALIDATING, VALIDATION_FAILED, SUCCEEDED -> true;
            default -> false;
        };
    }

    private static void requireOne(int affected, String operation) throws SQLException {
        if (affected != 1) {
            throw new SQLException(operation + " expected one row, affected " + affected);
        }
    }

    private static IllegalStateException repositoryFailure(String operation, SQLException error) {
        return new IllegalStateException("Unable to " + operation + ": " + error.getMessage(), error);
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
