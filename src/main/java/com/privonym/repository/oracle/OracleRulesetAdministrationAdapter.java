package com.privonym.repository.oracle;

import com.privonym.application.port.out.RulesetAdministrationRepository;
import com.privonym.application.service.JobCreationResult;
import com.privonym.application.service.JobUpdateResult;
import com.privonym.application.service.RulesetDetails;
import com.privonym.application.service.RulesetInventoryEntry;
import com.privonym.application.service.RulesetCatalogColumn;
import com.privonym.application.service.RulesetInventorySelection;
import com.privonym.application.service.RulesetJobView;
import com.privonym.application.service.RulesetSummary;
import com.privonym.application.service.RulesetVersionView;
import com.privonym.application.service.RulesetSqlExport;
import com.privonym.application.service.RulesetCloneResult;
import com.privonym.adapter.oracle.GeneratedOracleStatement;
import com.privonym.adapter.oracle.OracleUpdateGenerator;
import com.privonym.application.service.ExecuteJobCommand;
import com.privonym.application.service.ExecuteJobResult;
import com.privonym.application.service.ExecuteJobService;
import com.privonym.application.service.GenerateReportCommand;
import com.privonym.application.service.GenerateReportService;
import com.privonym.application.service.JobReport;
import com.privonym.application.service.JobExecutionCancelledException;
import com.privonym.application.service.PlanJobCommand;
import com.privonym.application.service.PlanJobService;
import com.privonym.application.service.PreflightJobCommand;
import com.privonym.application.service.PreflightJobService;
import com.privonym.application.service.ValidateJobCommand;
import com.privonym.application.service.ValidateJobService;
import com.privonym.domain.execution.ExecutionMode;
import com.privonym.domain.execution.RecoveryMode;
import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.JobStatus;
import java.time.Instant;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Oracle administration adapter for rulesets, their inventory and jobs. */
public final class OracleRulesetAdministrationAdapter
        implements RulesetAdministrationRepository, AutoCloseable {
    private static final String KEEP_PARAMETER_VALUE = "__PRIVONYM_KEEP__";
    private static final String SUMMARY_SELECT = """
            SELECT r.ruleset_id, r.code, r.name, NVL(r.description, '') description,
                   ds.data_source_id, ds.name data_source_name,c.technology_code,
                   e.code environment_code, e.name environment_name,
                   (SELECT COUNT(*) FROM ruleset_version rv
                     WHERE rv.ruleset_id = r.ruleset_id) version_count,
                   ((SELECT COUNT(*) FROM masking_rule mr
                     WHERE mr.ruleset_version_id = (
                           SELECT MAX(rv2.ruleset_version_id)
                             KEEP (DENSE_RANK LAST ORDER BY rv2.version_number)
                             FROM ruleset_version rv2
                            WHERE rv2.ruleset_id = r.ruleset_id)) +
                    (SELECT COUNT(*) FROM table_masking_rule tr
                     WHERE tr.ruleset_version_id = (
                           SELECT MAX(rv2.ruleset_version_id)
                             KEEP (DENSE_RANK LAST ORDER BY rv2.version_number)
                             FROM ruleset_version rv2
                            WHERE rv2.ruleset_id = r.ruleset_id))) inventory_count,
                   (SELECT COUNT(*) FROM anonymization_job j
                     JOIN ruleset_version rv3
                       ON rv3.ruleset_version_id = j.ruleset_version_id
                    WHERE rv3.ruleset_id = r.ruleset_id) job_count
              FROM ruleset r
              JOIN data_source ds ON ds.data_source_id = r.data_source_id
              JOIN connector c ON c.connector_id = ds.connector_id
              JOIN environment e ON e.environment_id = ds.environment_id
            """;

    private final String jdbcUrl;
    private final String username;
    private final char[] password;
    private final OracleJobPlanRepository rulesetPlans = new OracleJobPlanRepository();
    private final OracleUpdateGenerator updateGenerator = new OracleUpdateGenerator();

    public OracleRulesetAdministrationAdapter(String jdbcUrl, String username, char[] password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password").clone();
    }

    @Override
    public List<RulesetSummary> list() {
        String sql = SUMMARY_SELECT + " ORDER BY e.name, ds.name, r.name";
        List<RulesetSummary> result = new ArrayList<>();
        try (Connection connection = open(true);
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            while (row.next()) result.add(summary(row));
            return result;
        } catch (SQLException error) {
            throw failure("list rulesets", error);
        }
    }

    @Override
    public void delete(long rulesetId) {
        try (Connection connection = open(false)) {
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ruleset_id FROM ruleset WHERE ruleset_id = ? FOR UPDATE")) {
                    statement.setLong(1, rulesetId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Rule Set not found");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*) FROM anonymization_job j
                          JOIN ruleset_version rv ON rv.ruleset_version_id = j.ruleset_version_id
                         WHERE rv.ruleset_id = ?
                        """)) {
                    statement.setLong(1, rulesetId);
                    try (ResultSet row = statement.executeQuery()) {
                        row.next();
                        int jobs = row.getInt(1);
                        if (jobs > 0) throw new IllegalStateException(
                                "This Rule Set cannot be deleted because it owns " + jobs + " Job(s)");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        DELETE FROM transformation_mapping
                         WHERE transformation_import_id IN (
                               SELECT transformation_import_id FROM transformation_import
                                WHERE ruleset_version_id IN (
                                      SELECT ruleset_version_id FROM ruleset_version
                                       WHERE ruleset_id = ?))
                        """)) {
                    statement.setLong(1, rulesetId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        DELETE FROM transformation_import
                         WHERE ruleset_version_id IN (
                               SELECT ruleset_version_id FROM ruleset_version
                                WHERE ruleset_id = ?)
                        """)) {
                    statement.setLong(1, rulesetId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM ruleset_version WHERE ruleset_id = ?")) {
                    statement.setLong(1, rulesetId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM ruleset WHERE ruleset_id = ?")) {
                    statement.setLong(1, rulesetId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            if (error.getErrorCode() == 2292) throw new IllegalStateException(
                    "This Rule Set is referenced by retained execution or import history", error);
            throw failure("delete ruleset", error);
        }
    }

    @Override
    public void deleteJob(String reference) {
        try (Connection connection = open(false)) {
            try {
                long jobId;
                String status;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT job_id,status FROM anonymization_job WHERE job_reference=? FOR UPDATE")) {
                    statement.setString(1, reference);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Job not found");
                        jobId = row.getLong("job_id");
                        status = row.getString("status");
                    }
                }
                if (isActiveJobStatus(status)) {
                    throw new IllegalStateException(
                            "A Job cannot be deleted while it is " + status);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM anonymization_job WHERE job_id=?")) {
                    statement.setLong(1, jobId);
                    if (statement.executeUpdate() != 1) throw new SQLException("Job was not deleted");
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw failure("delete job", error);
        }
    }

    @Override
    public JobUpdateResult updateJob(String currentReference, String newReference,
            long rulesetId, int parallelDegree, String executionMode,
            int chunkSize, long chunkThreshold) {
        try (Connection connection = open(false)) {
            try {
                long jobId;
                long currentVersionId;
                long currentRulesetId;
                String currentStatus;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT j.job_id,j.status,j.ruleset_version_id,rv.ruleset_id
                          FROM anonymization_job j JOIN ruleset_version rv
                            ON rv.ruleset_version_id=j.ruleset_version_id
                         WHERE j.job_reference=? FOR UPDATE
                        """)) {
                    statement.setString(1, currentReference);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Job not found");
                        jobId = row.getLong("job_id");
                        currentStatus = row.getString("status");
                        currentVersionId = row.getLong("ruleset_version_id");
                        currentRulesetId = row.getLong("ruleset_id");
                    }
                }
                if (isActiveJobStatus(currentStatus)) {
                    throw new IllegalStateException(
                            "A Job cannot be modified while it is " + currentStatus);
                }

                long targetVersionId;
                long targetDataSourceId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT rv.ruleset_version_id,r.data_source_id
                          FROM ruleset r JOIN ruleset_version rv ON rv.ruleset_id=r.ruleset_id
                         WHERE r.ruleset_id=? AND rv.status='PUBLISHED'
                         ORDER BY rv.version_number DESC FETCH FIRST 1 ROW ONLY
                        """)) {
                    statement.setLong(1, rulesetId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException(
                                "The selected Rule Set has no published version");
                        targetVersionId = row.getLong("ruleset_version_id");
                        targetDataSourceId = row.getLong("data_source_id");
                    }
                }

                if (rulesetId != currentRulesetId
                        && rulesetHasJob(connection, rulesetId, jobId)) {
                    throw new IllegalStateException(
                            "The selected Rule Set is already linked to another Job");
                }

                boolean planReset = currentVersionId != targetVersionId;
                if (planReset) {
                    rebindJob(connection, jobId, targetVersionId, targetDataSourceId);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE anonymization_job
                           SET job_reference=?,parallel_degree=?,execution_mode=?,
                               chunk_size=?,chunk_threshold=?
                         WHERE job_id=?
                        """)) {
                    statement.setString(1, newReference);
                    statement.setInt(2, parallelDegree);
                    statement.setString(3, executionMode);
                    statement.setInt(4, chunkSize);
                    statement.setLong(5, chunkThreshold);
                    statement.setLong(6, jobId);
                    if (statement.executeUpdate() != 1) throw new SQLException("Job was not updated");
                }
                connection.commit();
                return new JobUpdateResult(jobId, newReference,
                        planReset ? "CREATED" : currentStatus, rulesetId,
                        targetVersionId, planReset);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            if (error.getErrorCode() == 1) throw new IllegalArgumentException(
                    "Job reference already exists: " + newReference);
            throw failure("update job", error);
        }
    }

    @Override
    public ExecuteJobResult executeJob(String reference) {
        long jobId;
        try (Connection connection = open(true);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT job_id FROM anonymization_job WHERE job_reference=?")) {
            statement.setString(1, reference);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Job was not found: " + reference);
                jobId = row.getLong(1);
            }
        } catch (SQLException error) {
            throw failure("resolve job reference", error);
        }
        try {
            JobReport report = executeJobToCompletion(jobId);
            return new ExecuteJobResult(report.jobId(), JobStatus.SUCCEEDED,
                    report.succeededSteps(), report.rowsProcessed());
        } catch (JobExecutionCancelledException cancelled) {
            return cancelledExecutionResult(jobId);
        } catch (RuntimeException error) {
            writeFailedExecutionReport(jobId, error);
            throw error;
        }
        /*
        try (Connection repository = open(false)) {
            long jobId;
            try (PreparedStatement statement = repository.prepareStatement(
                    "SELECT job_id FROM anonymization_job WHERE job_reference=?")) {
                statement.setString(1, reference);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("Job was not found: " + reference);
                    jobId = row.getLong(1);
                }
            }
            synchronizeJobWithLatestPublishedVersion(repository, jobId);
            OraclePlanningAdapter planning = new OraclePlanningAdapter(repository);
            AnonymizationJob job = planning.getByReference(reference);
            if (job.status() == JobStatus.SUCCEEDED
                    || job.status() == JobStatus.PREFLIGHT_FAILED
                    || job.status() == JobStatus.PLAN_FAILED
                    || job.status() == JobStatus.EXECUTION_FAILED
                    || job.status() == JobStatus.VALIDATION_FAILED
                    || job.status() == JobStatus.CANCELLED) {
                resetForRerun(repository, job.id().value(), job.status() == JobStatus.SUCCEEDED);
                job = planning.get(job.id());
            }
            JobExecutionSettings executionSettings = loadExecutionSettings(repository, jobId);
            OracleConnectionDetails details = planning.loadConnectionDetails(job.dataSourceId());
            String encryptedPassword;
            try (PreparedStatement statement = repository.prepareStatement("""
                    SELECT c.encrypted_password FROM data_source d
                      JOIN connector c ON c.connector_id = d.connector_id
                     WHERE d.data_source_id = ?
                    """)) {
                statement.setLong(1, job.dataSourceId().value());
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next() || row.getString(1) == null || row.getString(1).isBlank())
                        throw new IllegalStateException("Saved connection credentials are missing");
                    encryptedPassword = row.getString(1);
                }
            }
            char[] targetPassword = LocalCredentialStore.decrypt(encryptedPassword);
            try (Connection target = DriverManager.getConnection(
                    details.jdbcUrl(), details.username(), new String(targetPassword))) {
                target.setReadOnly(false);
                target.setAutoCommit(false);
                if (job.status() == JobStatus.DRAFT) {
                    new PlanJobService(planning, planning, planning, planning, planning, Instant::now)
                            .plan(new PlanJobCommand(job.id()));
                    job = planning.get(job.id());
                }
                if (job.status() == JobStatus.PLANNED) {
                    new PreflightJobService(planning, planning,
                            new OracleTargetPreflightAdapter(repository, target), planning, Instant::now)
                            .preflight(new PreflightJobCommand(job.id()));
                    job = planning.get(job.id());
                }
                if (job.status() != JobStatus.PREFLIGHT_PASSED) throw new IllegalStateException(
                        "Job cannot be executed from status " + job.status());
                ExecuteJobResult result = new ExecuteJobService(planning, planning,
                        new OracleDirectPlanExecutor(target), planning, Instant::now)
                        .execute(new ExecuteJobCommand(job.id(), ExecutionMode.DIRECT, RecoveryMode.NEW));
                repository.commit();
                return result;
            } catch (RuntimeException | SQLException error) {
                repository.rollback();
                throw error;
            } finally {
                Arrays.fill(targetPassword, '\0');
            }
        } catch (SQLException error) {
            throw failure("execute job", error);
        }
        */
    }

    @Override
    public void saveJobScript(String reference, String phase, String fileName, String content) {
        try (Connection connection = open(false)) {
            long jobId;
            String status;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT job_id,status FROM anonymization_job WHERE job_reference=? FOR UPDATE")) {
                statement.setString(1, reference);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("Job was not found: " + reference);
                    jobId = row.getLong(1);
                    status = row.getString(2);
                }
            }
            if (isActiveStatus(status)) throw new IllegalStateException(
                    "Scripts cannot be changed while the Job is active");
            if (content == null || content.isBlank()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM job_script WHERE job_id=? AND script_phase=?")) {
                    statement.setLong(1, jobId); statement.setString(2, phase); statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement("""
                        MERGE INTO job_script target
                        USING (SELECT ? job_id, ? script_phase FROM dual) source
                           ON (target.job_id=source.job_id AND target.script_phase=source.script_phase)
                         WHEN MATCHED THEN UPDATE SET file_name=?,script_content=?,updated_at=SYSTIMESTAMP
                         WHEN NOT MATCHED THEN INSERT
                              (job_script_id,job_id,script_phase,file_name,script_content,created_at,updated_at)
                              VALUES (job_script_seq.NEXTVAL,?,?,?,?,SYSTIMESTAMP,SYSTIMESTAMP)
                        """)) {
                    statement.setLong(1, jobId); statement.setString(2, phase);
                    statement.setString(3, fileName); statement.setString(4, content);
                    statement.setLong(5, jobId); statement.setString(6, phase);
                    statement.setString(7, fileName); statement.setString(8, content);
                    statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException error) {
            throw failure("save Job script", error);
        }
    }

    @Override
    public ExecuteJobResult executeJob(long jobId) {
        String reference;
        try (Connection connection = open(true);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT job_reference FROM anonymization_job WHERE job_id=?")) {
            statement.setLong(1, jobId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Job was not found: " + jobId);
                reference = row.getString(1);
            }
        } catch (SQLException error) {
            throw failure("resolve job identifier", error);
        }
        return executeJob(reference);
    }

    private ExecuteJobResult cancelledExecutionResult(long jobId) {
        try (Connection connection = open(true);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT (SELECT COUNT(*) FROM job_step
                                 WHERE job_id=j.job_id AND status='SUCCEEDED'),
                               (SELECT NVL(SUM(c.rows_processed),0)
                                  FROM job_chunk c JOIN job_step s
                                    ON s.job_step_id=c.job_step_id
                                 WHERE s.job_id=j.job_id AND c.status='SUCCEEDED')
                          FROM anonymization_job j WHERE j.job_id=? AND j.status='CANCELLED'
                        """)) {
            statement.setLong(1, jobId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException(
                        "Cancelled Job status was not persisted");
                return new ExecuteJobResult(new com.privonym.domain.job.JobId(jobId),
                        JobStatus.CANCELLED, row.getInt(1), row.getLong(2));
            }
        } catch (SQLException error) {
            throw failure("load cancelled execution result", error);
        }
    }

    @Override
    public void cancelJob(long jobId) {
        try (Connection connection = open(false)) {
            try {
                String status;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT status FROM anonymization_job WHERE job_id=? FOR UPDATE")) {
                    statement.setLong(1, jobId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException(
                                "Job was not found: " + jobId);
                        status = row.getString(1);
                    }
                }
                if (!List.of("RUNNING", "RETRYING", "PAUSING").contains(status)) {
                    throw new IllegalStateException(
                            "Job execution cannot be cancelled from status " + status);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE anonymization_job
                           SET status='CANCELLING',heartbeat_at=SYSTIMESTAMP
                         WHERE job_id=? AND status IN ('RUNNING','RETRYING','PAUSING')
                        """)) {
                    statement.setLong(1, jobId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Job status changed before cancellation");
                    }
                }
                boolean localExecution = OracleParallelPlanExecutor.cancel(jobId);
                if (!localExecution) {
                    finalizeOrphanedExecution(connection, jobId);
                }
                try (PreparedStatement statement = connection.prepareStatement(localExecution ? """
                        INSERT INTO job_event
                            (job_id,event_timestamp,severity,event_code,message,details_json)
                        VALUES (?,SYSTIMESTAMP,'WARN','JOB_CANCELLATION_REQUESTED',
                                'Cancellation requested from the console',NULL)
                        """ : """
                        INSERT INTO job_event
                            (job_id,event_timestamp,severity,event_code,message,details_json)
                        VALUES (?,SYSTIMESTAMP,'WARN','ORPHANED_EXECUTION_CANCELLED',
                                'Orphaned execution finalized after server restart',NULL)
                        """)) {
                    statement.setLong(1, jobId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw failure("cancel job execution", error);
        }
    }

    private static void finalizeOrphanedExecution(Connection connection, long jobId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE job_step_attempt
                   SET status='CANCELLED',ended_at=SYSTIMESTAMP,
                       heartbeat_at=SYSTIMESTAMP,error_code=NULL,error_message=NULL
                 WHERE job_step_id IN
                       (SELECT job_step_id FROM job_step WHERE job_id=?)
                   AND status='RUNNING'
                """)) {
            statement.setLong(1, jobId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE job_chunk SET status='CANCELLED',ended_at=SYSTIMESTAMP,
                       error_message='Orphaned execution cancelled after server restart'
                 WHERE job_step_id IN
                       (SELECT job_step_id FROM job_step WHERE job_id=?)
                   AND status='RUNNING'
                """)) {
            statement.setLong(1, jobId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE job_step SET status='CANCELLED',ended_at=SYSTIMESTAMP
                 WHERE job_id=? AND status='RUNNING'
                """)) {
            statement.setLong(1, jobId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE anonymization_job
                   SET status='CANCELLED',ended_at=SYSTIMESTAMP,
                       heartbeat_at=SYSTIMESTAMP,error_code=NULL,error_message=NULL
                 WHERE job_id=? AND status='CANCELLING'
                """)) {
            statement.setLong(1, jobId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Orphaned Job cancellation status changed concurrently");
            }
        }
    }

    /** Runs or resumes a Job through validation and final reporting. */
    public JobReport executeJobToCompletion(long jobId) {
        try (Connection repository = open(false)) {
            synchronizeJobWithLatestPublishedVersion(repository, jobId);
            OraclePlanningAdapter planning = new OraclePlanningAdapter(repository);
            AnonymizationJob job = planning.get(new com.privonym.domain.job.JobId(jobId));
            if (job.status() == JobStatus.SUCCEEDED
                    || job.status() == JobStatus.PREFLIGHT_FAILED
                    || job.status() == JobStatus.PLAN_FAILED
                    || job.status() == JobStatus.EXECUTION_FAILED
                    || job.status() == JobStatus.VALIDATION_FAILED
                    || job.status() == JobStatus.CANCELLED) {
                resetForRerun(repository, job.id().value(), job.status() == JobStatus.SUCCEEDED);
                job = planning.get(job.id());
            }
            JobExecutionSettings executionSettings = loadExecutionSettings(repository, jobId);
            OracleConnectionDetails details = planning.loadConnectionDetails(job.dataSourceId());
            String encryptedPassword;
            try (PreparedStatement statement = repository.prepareStatement("""
                    SELECT c.encrypted_password FROM data_source d
                      JOIN connector c ON c.connector_id=d.connector_id
                     WHERE d.data_source_id=?
                    """)) {
                statement.setLong(1, job.dataSourceId().value());
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next() || row.getString(1) == null || row.getString(1).isBlank()) {
                        throw new IllegalStateException("Saved connection credentials are missing");
                    }
                    encryptedPassword = row.getString(1);
                }
            }
            char[] targetPassword = LocalCredentialStore.decrypt(encryptedPassword);
            try (Connection target = DriverManager.getConnection(
                    details.jdbcUrl(), details.username(), new String(targetPassword))) {
                target.setAutoCommit(false);
                if ("SQLSERVER".equals(details.technologyCode())) {
                    try (Statement statement = target.createStatement()) {
                        statement.execute("SET QUOTED_IDENTIFIER ON");
                        statement.execute("SET XACT_ABORT ON");
                    }
                }
                if (job.status() == JobStatus.DRAFT) {
                    new PlanJobService(planning, planning, planning, planning, planning, Instant::now)
                            .plan(new PlanJobCommand(job.id()));
                    job = planning.get(job.id());
                }
                if (job.status() == JobStatus.PLANNED) {
                    target.setReadOnly(true);
                    com.privonym.application.port.out.TargetPreflightAdapter targetPreflight =
                            switch (details.technologyCode()) {
                                case "POSTGRESQL" -> new com.privonym.repository.postgresql.PostgresqlTargetPreflightAdapter(repository, target);
                                case "SQLSERVER" -> new com.privonym.repository.sqlserver.SqlServerTargetPreflightAdapter(repository, target);
                                default -> new OracleTargetPreflightAdapter(repository, target);
                            };
                    new PreflightJobService(planning, planning, targetPreflight,
                            planning, Instant::now)
                            .preflight(new PreflightJobCommand(job.id()));
                    target.rollback();
                    target.setReadOnly(false);
                    job = planning.get(job.id());
                }
                if (job.status() == JobStatus.PREFLIGHT_PASSED) {
                    executeStoredScript(repository, target, job.id().value(), "PRE");
                    boolean relationshipAware = hasRelationshipAwareToken(repository,
                            job.id().value());
                    if (relationshipAware) {
                        new ExecuteJobService(planning, planning,
                                new OracleMeasuredPlanExecutor(repository, target),
                                planning, Instant::now).execute(new ExecuteJobCommand(
                                        job.id(), ExecutionMode.DIRECT, RecoveryMode.NEW));
                    } else {
                        try (OracleParallelPlanExecutor executor = new OracleParallelPlanExecutor(
                                jdbcUrl, username, password, details.jdbcUrl(), details.username(),
                                targetPassword, executionSettings.parallelDegree(),
                                executionSettings.executionMode(), executionSettings.chunkSize(),
                                executionSettings.chunkThreshold())) {
                            new ExecuteJobService(planning, planning, executor,
                                    planning, Instant::now).execute(new ExecuteJobCommand(
                                            job.id(), "DIRECT".equals(executionSettings.executionMode())
                                                    ? ExecutionMode.DIRECT : ExecutionMode.CHUNKED,
                                            RecoveryMode.NEW));
                        }
                    }
                    job = planning.get(job.id());
                }
                if (job.status() == JobStatus.VALIDATING && !validationSucceeded(repository, job.id().value())
                        || job.status() == JobStatus.VALIDATION_FAILED) {
                    target.setReadOnly(true);
                    new ValidateJobService(planning, new OracleTargetDataValidator(repository, target),
                            planning, Instant::now).validate(new ValidateJobCommand(job.id()));
                    target.rollback();
                    target.setReadOnly(false);
                    job = planning.get(job.id());
                }
                if (job.status() != JobStatus.VALIDATING) {
                    throw new IllegalStateException("Job cannot be completed from status " + job.status());
                }
                executeStoredScript(repository, target, job.id().value(), "POST");
                // Validate the final state, including changes made by the existing POST phase.
                target.setReadOnly(true);
                try {
                    new ValidateJobService(planning, new OracleTargetDataValidator(repository, target),
                            planning, Instant::now).validate(new ValidateJobCommand(job.id()));
                } finally {
                    target.rollback();
                    target.setReadOnly(false);
                }
                JobReport report = new GenerateReportService(planning, planning, Instant::now)
                        .generate(new GenerateReportCommand(job.id()));
                ExecutionReportPdfWriter.write(report, Instant.now());
                return report;
            } finally {
                Arrays.fill(targetPassword, '\0');
            }
        } catch (SQLException error) {
            throw failure("run Job to completion", error);
        }
    }

    private static boolean validationSucceeded(Connection connection, long jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM job_step
                 WHERE job_id=? AND step_type='VALIDATE_DATA' AND status='SUCCEEDED'
                """)) {
            statement.setLong(1, jobId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1) > 0;
            }
        }
    }

    private static boolean hasRelationshipAwareToken(Connection connection,long jobId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM anonymization_job j
                JOIN masking_rule mr ON mr.ruleset_version_id=j.ruleset_version_id
                     AND mr.enabled='Y'
                JOIN algorithm_version av ON av.algorithm_version_id=mr.algorithm_version_id
                JOIN algorithm a ON a.algorithm_id=av.algorithm_id
                WHERE j.job_id=? AND a.code='RELATIONSHIP_AWARE_TOKEN'
                """)) {
            statement.setLong(1,jobId);
            try (ResultSet row=statement.executeQuery()) { row.next(); return row.getInt(1)>0; }
        }
    }

    private static void executeStoredScript(Connection repository, Connection target, long jobId,
            String phase) throws SQLException {
        String fileName = null;
        String content = null;
        try (PreparedStatement statement = repository.prepareStatement(
                "SELECT file_name,script_content FROM job_script WHERE job_id=? AND script_phase=?")) {
            statement.setLong(1, jobId); statement.setString(2, phase);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) { fileName = row.getString(1); content = row.getString(2); }
            }
        }
        if (content == null || content.isBlank()) return;
        try {
            for (String sql : splitSqlScript(content)) {
                try (Statement statement = target.createStatement()) { statement.execute(sql); }
            }
            target.commit();
        } catch (SQLException error) {
            target.rollback();
            try (PreparedStatement statement = repository.prepareStatement("""
                    UPDATE anonymization_job SET status='EXECUTION_FAILED',ended_at=SYSTIMESTAMP,
                           error_code=?,error_message=?,heartbeat_at=SYSTIMESTAMP WHERE job_id=?
                    """)) {
                statement.setString(1, phase + "_SCRIPT_FAILED");
                statement.setString(2, phase + "-execution script " + fileName + " failed: "
                        + error.getMessage());
                statement.setLong(3, jobId); statement.executeUpdate(); repository.commit();
            }
            throw error;
        }
    }

    static List<String> splitSqlScript(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        boolean quoted = false;
        for (String line : content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (line.trim().equals("/") && !block.toString().isBlank()) {
                statements.add(block.toString().trim()); block.setLength(0); continue;
            }
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\'' && quoted && i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                    block.append("''"); i++; continue;
                }
                if (c == '\'') quoted = !quoted;
                if (c == ';' && !quoted && !looksLikePlSql(block)) {
                    String sql = block.toString().trim(); if (!sql.isEmpty()) statements.add(sql);
                    block.setLength(0);
                } else block.append(c);
            }
            block.append('\n');
        }
        String sql = block.toString().trim(); if (!sql.isEmpty()) statements.add(sql);
        return statements;
    }

    private static boolean looksLikePlSql(StringBuilder sql) {
        String prefix = sql.toString().stripLeading().toUpperCase();
        return prefix.startsWith("BEGIN") || prefix.startsWith("DECLARE")
                || prefix.startsWith("CREATE OR REPLACE PROCEDURE")
                || prefix.startsWith("CREATE OR REPLACE FUNCTION")
                || prefix.startsWith("CREATE OR REPLACE PACKAGE")
                || prefix.startsWith("CREATE OR REPLACE TRIGGER");
    }

    private static boolean isActiveStatus(String status) {
        return List.of("PLANNING","PREFLIGHTING","RUNNING","RETRYING","PAUSING",
                "CANCELLING","VALIDATING").contains(status);
    }

    private static JobExecutionSettings loadExecutionSettings(Connection connection, long jobId)
            throws SQLException {
        String projection = supportsExtendedJobConfiguration(connection)
                ? "parallel_degree,execution_mode,chunk_size,chunk_threshold"
                : "parallel_degree,'AUTO' execution_mode,100000 chunk_size,1000000 chunk_threshold";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + projection + " FROM anonymization_job WHERE job_id=?")) {
            statement.setLong(1, jobId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Job was not found: " + jobId);
                return new JobExecutionSettings(row.getInt(1), row.getString(2),
                        row.getInt(3), row.getLong(4));
            }
        }
    }

    private static boolean supportsExtendedJobConfiguration(Connection connection)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM user_tab_columns
                 WHERE table_name='ANONYMIZATION_JOB'
                   AND column_name IN ('EXECUTION_MODE','CHUNK_SIZE','CHUNK_THRESHOLD')
                """); ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getInt(1) == 3;
        }
    }

    private static void resetForRerun(Connection connection, long jobId,
            boolean clearSuccessfulChunks) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE anonymization_job
                   SET status='CREATED', planned_at=NULL, started_at=NULL, ended_at=NULL,
                       heartbeat_at=NULL, error_code=NULL, error_message=NULL
                 WHERE job_id=? AND status IN
                       ('SUCCEEDED','PREFLIGHT_FAILED','EXECUTION_FAILED','VALIDATION_FAILED',
                        'FAILED','PLAN_FAILED','CANCELLED')
                """)) {
            statement.setLong(1, jobId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Retryable Job status changed concurrently");
            }
        }
        if (clearSuccessfulChunks) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM job_chunk WHERE job_step_id IN
                        (SELECT job_step_id FROM job_step WHERE job_id=?)
                    """)) {
                statement.setLong(1, jobId);
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE job_step
                   SET status=CASE WHEN step_type='PRECHECK' THEN 'READY' ELSE 'PENDING' END,
                       started_at=NULL, ended_at=NULL
                 WHERE job_id=?
                """)) {
            statement.setLong(1, jobId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM validation_result WHERE job_id=?")) {
            statement.setLong(1, jobId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM job_metric WHERE job_id=? AND job_step_id IS NULL")) {
            statement.setLong(1, jobId);
            statement.executeUpdate();
        }
        connection.commit();
    }

    private void writeFailedExecutionReport(long jobId, RuntimeException executionError) {
        try (Connection connection = open(true)) {
            String sql = ExecutionFailureSummary.SQL;
            String reference;
            String status;
            int succeededSteps;
            long rowsProcessed;
            long executionDurationSeconds;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, jobId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) return;
                    reference = row.getString("job_reference");
                    status = row.getString("status");
                    succeededSteps = row.getInt("succeeded_steps");
                    rowsProcessed = row.getLong("rows_processed");
                    java.sql.Timestamp started=row.getTimestamp("started_at"),ended=row.getTimestamp("report_end_at");
                    executionDurationSeconds=started==null||ended==null?0:Math.max(0,java.time.Duration.between(started.toInstant(),ended.toInstant()).getSeconds());
                }
            }
            List<com.privonym.application.service.ValidationMetric> metrics = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT metric_name, SUM(metric_value) metric_value
                      FROM job_metric WHERE job_id=?
                     GROUP BY metric_name ORDER BY metric_name
                    """)) {
                statement.setLong(1, jobId);
                try (ResultSet row = statement.executeQuery()) {
                    while (row.next()) metrics.add(
                            new com.privonym.application.service.ValidationMetric(
                                    row.getString("metric_name"), row.getLong("metric_value")));
                }
            }
            JobReport report = new JobReport(
                    new com.privonym.domain.job.JobId(jobId),
                    new com.privonym.domain.job.JobReference(reference),
                    rowsProcessed, succeededSteps, metrics,executionDurationSeconds);
            ExecutionReportPdfWriter.write(report, status,
                    String.valueOf(executionError.getMessage()), Instant.now());
        } catch (SQLException | RuntimeException reportError) {
            executionError.addSuppressed(reportError);
        }
    }

    @Override
    public RulesetDetails get(long rulesetId, Long requestedVersionId) {
        try (Connection connection = open(true)) {
            RulesetSummary ruleset = loadSummary(connection, rulesetId);
            List<RulesetVersionView> versions = loadVersions(connection, rulesetId);
            if (versions.isEmpty()) throw new IllegalArgumentException("Ruleset has no version");
            long versionId = requestedVersionId == null ? versions.get(0).id() : requestedVersionId;
            if (versions.stream().noneMatch(item -> item.id() == versionId)) {
                throw new IllegalArgumentException("Ruleset version was not found");
            }
            return new RulesetDetails(ruleset, versionId, versions,
                    loadInventory(connection, versionId), loadJobs(connection, rulesetId));
        } catch (SQLException error) {
            throw failure("load ruleset", error);
        }
    }

    @Override
    public RulesetVersionView createDraft(long rulesetId, long sourceVersionId, String createdBy) {
        try (Connection connection = open(false)) {
            try {
                int nextVersion;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT rv.status
                          FROM ruleset_version rv
                         WHERE rv.ruleset_id=? AND rv.ruleset_version_id=? FOR UPDATE
                        """)) {
                    statement.setLong(1, rulesetId);
                    statement.setLong(2, sourceVersionId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Ruleset version was not found");
                        if (!"PUBLISHED".equals(row.getString("status"))) {
                            throw new IllegalArgumentException("A draft can only be created from a published version");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT NVL(MAX(version_number),0)+1 next_version
                          FROM ruleset_version WHERE ruleset_id=?
                        """)) {
                    statement.setLong(1, rulesetId);
                    try (ResultSet row = statement.executeQuery()) {
                        row.next();
                        if (hasDraft(connection, rulesetId)) {
                            throw new IllegalStateException("This Rule Set already has a DRAFT version");
                        }
                        nextVersion = row.getInt("next_version");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ruleset_version(ruleset_id,version_number,status,description,created_by)
                        VALUES(?,?,'DRAFT',?,?)
                        """)) {
                    statement.setLong(1, rulesetId);
                    statement.setInt(2, nextVersion);
                    statement.setString(3, "Draft created from published version " + sourceVersionId);
                    statement.setString(4, createdBy);
                    statement.executeUpdate();
                }
                long draftId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ruleset_version_id FROM ruleset_version WHERE ruleset_id=? AND version_number=?")) {
                    statement.setLong(1, rulesetId);
                    statement.setInt(2, nextVersion);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new SQLException("Unable to reload the new draft version");
                        draftId = row.getLong(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO masking_rule(ruleset_version_id,catalog_column_id,algorithm_version_id,
                                                 execution_order,filter_expression,enabled)
                        SELECT ?,mr.catalog_column_id,mr.algorithm_version_id,mr.execution_order,
                               mr.filter_expression,mr.enabled
                          FROM masking_rule mr
                          JOIN catalog_column cc ON cc.catalog_column_id=mr.catalog_column_id
                          JOIN catalog_table ct ON ct.catalog_table_id=cc.catalog_table_id
                          JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                         WHERE mr.ruleset_version_id=?
                           AND ct.synchronized_at >= cs.synchronized_at
                           AND cc.synchronized_at >= cs.synchronized_at
                        """)) {
                    statement.setLong(1, draftId);
                    statement.setLong(2, sourceVersionId);
                    statement.executeUpdate();
                }
                if (repositoryObjectExists(connection, "RULE_PARAMETER")) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO rule_parameter(rule_id,parameter_definition_id,parameter_value)
                            SELECT target_rule.rule_id,p.parameter_definition_id,p.parameter_value
                              FROM masking_rule source_rule
                              JOIN rule_parameter p ON p.rule_id=source_rule.rule_id
                              JOIN masking_rule target_rule
                                ON target_rule.ruleset_version_id=?
                               AND target_rule.catalog_column_id=source_rule.catalog_column_id
                             WHERE source_rule.ruleset_version_id=?
                            """)) {
                        statement.setLong(1, draftId);
                        statement.setLong(2, sourceVersionId);
                        statement.executeUpdate();
                    }
                }
                if (repositoryObjectExists(connection, "TABLE_MASKING_RULE")) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO table_masking_rule(ruleset_version_id,catalog_table_id,algorithm_version_id,
                                                           execution_order,filter_expression,enabled)
                            SELECT ?,tr.catalog_table_id,tr.algorithm_version_id,tr.execution_order,
                                   tr.filter_expression,tr.enabled
                              FROM table_masking_rule tr
                              JOIN catalog_table ct ON ct.catalog_table_id=tr.catalog_table_id
                              JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                             WHERE tr.ruleset_version_id=?
                               AND ct.synchronized_at >= cs.synchronized_at
                            """)) {
                        statement.setLong(1, draftId);
                        statement.setLong(2, sourceVersionId);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return loadVersions(connection, rulesetId).stream()
                        .filter(version -> version.id() == draftId).findFirst()
                        .orElseThrow(() -> new IllegalStateException("New draft version was not found"));
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw failure("create draft ruleset version", error);
        }
    }

    @Override
    public RulesetCloneResult cloneRuleset(long sourceRulesetId, long targetDataSourceId,
            String name, String createdBy) {
        try (Connection connection = open(false)) {
            try {
                String targetSchema;
                String targetTechnology;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT NVL(c.schema_name, NVL(c.username_value, '')) target_schema,
                               c.technology_code
                          FROM data_source ds JOIN connector c ON c.connector_id=ds.connector_id
                         WHERE ds.data_source_id=? AND ds.enabled='Y' FOR UPDATE
                        """)) {
                    statement.setLong(1, targetDataSourceId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException(
                                "Target connection was not found or is disabled");
                        targetSchema = value(row, "target_schema");
                        targetTechnology = value(row, "technology_code");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM ruleset WHERE data_source_id=?")) {
                    statement.setLong(1, targetDataSourceId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (row.next()) throw new IllegalStateException(
                                "The target connection already owns a Rule Set");
                    }
                }

                long sourceVersionId;
                String sourceTechnology;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT rv.ruleset_version_id,c.technology_code
                          FROM ruleset r
                          JOIN data_source ds ON ds.data_source_id=r.data_source_id
                          JOIN connector c ON c.connector_id=ds.connector_id
                          JOIN ruleset_version rv ON rv.ruleset_id=r.ruleset_id
                         WHERE r.ruleset_id=?
                         ORDER BY rv.version_number DESC FETCH FIRST 1 ROW ONLY
                        """)) {
                    statement.setLong(1, sourceRulesetId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException(
                                "Source Rule Set or version was not found");
                        sourceVersionId = row.getLong("ruleset_version_id");
                        sourceTechnology = value(row, "technology_code");
                    }
                }
                if (!sourceTechnology.equalsIgnoreCase(targetTechnology)) {
                    throw new IllegalArgumentException(
                            "Source and target connections must use the same technology");
                }

                int sourceColumnRules = count(connection,
                        "SELECT COUNT(*) FROM masking_rule WHERE ruleset_version_id=?",
                        sourceVersionId);
                int sourceTableRules = count(connection,
                        "SELECT COUNT(*) FROM table_masking_rule WHERE ruleset_version_id=?",
                        sourceVersionId);
                String code = generatedRulesetCode(name, targetDataSourceId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ruleset(data_source_id,code,name,description,owner_name)
                        VALUES(?,?,?,'Cloned from Rule Set ' || ?,?)
                        """)) {
                    statement.setLong(1, targetDataSourceId);
                    statement.setString(2, code);
                    statement.setString(3, name);
                    statement.setLong(4, sourceRulesetId);
                    statement.setString(5, createdBy);
                    statement.executeUpdate();
                }
                long rulesetId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ruleset_id FROM ruleset WHERE data_source_id=?")) {
                    statement.setLong(1, targetDataSourceId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new SQLException("Unable to reload cloned Rule Set");
                        rulesetId = row.getLong(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ruleset_version(ruleset_id,version_number,status,description,created_by)
                        VALUES(?,1,'DRAFT','Draft cloned from Rule Set ' || ?,?)
                        """)) {
                    statement.setLong(1, rulesetId);
                    statement.setLong(2, sourceRulesetId);
                    statement.setString(3, createdBy);
                    statement.executeUpdate();
                }
                long versionId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ruleset_version_id FROM ruleset_version WHERE ruleset_id=? AND version_number=1")) {
                    statement.setLong(1, rulesetId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new SQLException("Unable to reload cloned draft version");
                        versionId = row.getLong(1);
                    }
                }

                int columnRules;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO masking_rule(ruleset_version_id,catalog_column_id,
                                                 algorithm_version_id,consistency_domain_id,
                                                 lookup_dataset_id,execution_order,
                                                 filter_expression,enabled)
                        SELECT ?,target_column.catalog_column_id,source_rule.algorithm_version_id,
                               source_rule.consistency_domain_id,source_rule.lookup_dataset_id,
                               source_rule.execution_order,source_rule.filter_expression,source_rule.enabled
                          FROM masking_rule source_rule
                          JOIN catalog_column source_column
                            ON source_column.catalog_column_id=source_rule.catalog_column_id
                          JOIN catalog_table source_table
                            ON source_table.catalog_table_id=source_column.catalog_table_id
                          JOIN catalog_schema target_schema
                            ON target_schema.data_source_id=?
                           AND UPPER(target_schema.schema_name)=UPPER(?)
                          JOIN catalog_table target_table
                            ON target_table.catalog_schema_id=target_schema.catalog_schema_id
                           AND UPPER(target_table.table_name)=UPPER(source_table.table_name)
                          JOIN catalog_column target_column
                            ON target_column.catalog_table_id=target_table.catalog_table_id
                           AND UPPER(target_column.column_name)=UPPER(source_column.column_name)
                         WHERE source_rule.ruleset_version_id=?
                        """)) {
                    statement.setLong(1, versionId);
                    statement.setLong(2, targetDataSourceId);
                    statement.setString(3, targetSchema);
                    statement.setLong(4, sourceVersionId);
                    columnRules = statement.executeUpdate();
                }
                if (repositoryObjectExists(connection, "RULE_PARAMETER")) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO rule_parameter(rule_id,parameter_definition_id,parameter_value)
                            SELECT target_rule.rule_id,parameter.parameter_definition_id,
                                   parameter.parameter_value
                              FROM masking_rule source_rule
                              JOIN rule_parameter parameter ON parameter.rule_id=source_rule.rule_id
                              JOIN catalog_column source_column
                                ON source_column.catalog_column_id=source_rule.catalog_column_id
                              JOIN catalog_table source_table
                                ON source_table.catalog_table_id=source_column.catalog_table_id
                              JOIN masking_rule target_rule ON target_rule.ruleset_version_id=?
                              JOIN catalog_column target_column
                                ON target_column.catalog_column_id=target_rule.catalog_column_id
                               AND UPPER(target_column.column_name)=UPPER(source_column.column_name)
                              JOIN catalog_table target_table
                                ON target_table.catalog_table_id=target_column.catalog_table_id
                               AND UPPER(target_table.table_name)=UPPER(source_table.table_name)
                              JOIN catalog_schema target_schema
                                ON target_schema.catalog_schema_id=target_table.catalog_schema_id
                               AND target_schema.data_source_id=?
                               AND UPPER(target_schema.schema_name)=UPPER(?)
                             WHERE source_rule.ruleset_version_id=?
                            """)) {
                        statement.setLong(1, versionId);
                        statement.setLong(2, targetDataSourceId);
                        statement.setString(3, targetSchema);
                        statement.setLong(4, sourceVersionId);
                        statement.executeUpdate();
                    }
                }
                int tableRules;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO table_masking_rule(ruleset_version_id,catalog_table_id,
                                                       algorithm_version_id,execution_order,
                                                       filter_expression,enabled)
                        SELECT ?,target_table.catalog_table_id,source_rule.algorithm_version_id,
                               source_rule.execution_order,source_rule.filter_expression,
                               source_rule.enabled
                          FROM table_masking_rule source_rule
                          JOIN catalog_table source_table
                            ON source_table.catalog_table_id=source_rule.catalog_table_id
                          JOIN catalog_schema target_schema
                            ON target_schema.data_source_id=?
                           AND UPPER(target_schema.schema_name)=UPPER(?)
                          JOIN catalog_table target_table
                            ON target_table.catalog_schema_id=target_schema.catalog_schema_id
                           AND UPPER(target_table.table_name)=UPPER(source_table.table_name)
                         WHERE source_rule.ruleset_version_id=?
                        """)) {
                    statement.setLong(1, versionId);
                    statement.setLong(2, targetDataSourceId);
                    statement.setString(3, targetSchema);
                    statement.setLong(4, sourceVersionId);
                    tableRules = statement.executeUpdate();
                }
                connection.commit();
                int skipped = sourceColumnRules + sourceTableRules - columnRules - tableRules;
                return new RulesetCloneResult(rulesetId, versionId, sourceVersionId,
                        columnRules, tableRules, skipped);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw failure("clone Rule Set", error);
        }
    }

    private static int count(Connection connection, String sql, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static String generatedRulesetCode(String name, long dataSourceId) {
        String base = name.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) base = "RULESET_" + base;
        String suffix = "_" + dataSourceId;
        int maximum = 100 - suffix.length();
        if (base.length() > maximum) base = base.substring(0, maximum);
        return base + suffix;
    }

    @Override
    public void deleteVersion(long rulesetId, long versionId) {
        try (Connection connection = open(false)) {
            try {
                int versionNumber;
                int latestVersionNumber;
                int versionCount;
                long dataSourceId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT rv.version_number,
                               (SELECT MAX(x.version_number) FROM ruleset_version x
                                 WHERE x.ruleset_id=rv.ruleset_id) latest_version_number,
                               (SELECT COUNT(*) FROM ruleset_version x
                                 WHERE x.ruleset_id=rv.ruleset_id) version_count,
                               r.data_source_id
                          FROM ruleset_version rv JOIN ruleset r ON r.ruleset_id=rv.ruleset_id
                         WHERE rv.ruleset_id=? AND rv.ruleset_version_id=? FOR UPDATE
                        """)) {
                    statement.setLong(1, rulesetId);
                    statement.setLong(2, versionId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Ruleset version was not found");
                        versionNumber = row.getInt("version_number");
                        latestVersionNumber = row.getInt("latest_version_number");
                        versionCount = row.getInt("version_count");
                        dataSourceId = row.getLong("data_source_id");
                    }
                }
                if (versionNumber != latestVersionNumber) {
                    throw new IllegalStateException("Delete newer versions first");
                }
                if (versionCount <= 1) {
                    throw new IllegalStateException("The first Rule Set version cannot be deleted");
                }
                long fallbackVersionId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT ruleset_version_id FROM ruleset_version
                         WHERE ruleset_id=? AND ruleset_version_id<>? AND status='PUBLISHED'
                         ORDER BY version_number DESC FETCH FIRST 1 ROW ONLY
                        """)) {
                    statement.setLong(1, rulesetId);
                    statement.setLong(2, versionId);
                    try (ResultSet row = statement.executeQuery()) {
                        fallbackVersionId = row.next() ? row.getLong(1) : 0;
                    }
                }
                List<Long> affectedJobs = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT job_id, status FROM anonymization_job
                         WHERE ruleset_version_id=? FOR UPDATE
                        """)) {
                    statement.setLong(1, versionId);
                    try (ResultSet row = statement.executeQuery()) {
                        while (row.next()) {
                            String status = row.getString("status");
                            if (isActiveJobStatus(status)) {
                                throw new IllegalStateException("Version " + versionNumber
                                        + " cannot be deleted while Job " + row.getLong("job_id")
                                        + " is " + status);
                            }
                            affectedJobs.add(row.getLong("job_id"));
                        }
                    }
                }
                if (!affectedJobs.isEmpty() && fallbackVersionId == 0) {
                    throw new IllegalStateException("No preceding published version is available");
                }
                for (long jobId : affectedJobs) {
                    rebindJob(connection, jobId, fallbackVersionId, dataSourceId);
                }
                deleteTransformationHistory(connection, versionId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM ruleset_version WHERE ruleset_id=? AND ruleset_version_id=?")) {
                    statement.setLong(1, rulesetId);
                    statement.setLong(2, versionId);
                    if (statement.executeUpdate() != 1) throw new SQLException("Version was not deleted");
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw failure("delete ruleset version", error);
        }
    }

    private static void deleteTransformationHistory(Connection connection, long versionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM transformation_mapping
                 WHERE transformation_import_id IN (
                       SELECT transformation_import_id FROM transformation_import
                        WHERE ruleset_version_id = ?)
                """)) {
            statement.setLong(1, versionId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM transformation_import WHERE ruleset_version_id = ?")) {
            statement.setLong(1, versionId);
            statement.executeUpdate();
        }
    }

    private static void synchronizeJobWithLatestPublishedVersion(Connection connection, long jobId)
            throws SQLException {
        long currentVersionId;
        long latestVersionId;
        long dataSourceId;
        String status;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT j.ruleset_version_id, j.status, r.data_source_id,
                       (SELECT ruleset_version_id FROM ruleset_version latest
                         WHERE latest.ruleset_id=rv.ruleset_id AND latest.status='PUBLISHED'
                         ORDER BY latest.version_number DESC FETCH FIRST 1 ROW ONLY) latest_version_id
                  FROM anonymization_job j
                  JOIN ruleset_version rv ON rv.ruleset_version_id=j.ruleset_version_id
                  JOIN ruleset r ON r.ruleset_id=rv.ruleset_id
                 WHERE j.job_id=? FOR UPDATE
                """)) {
            statement.setLong(1, jobId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Job was not found: " + jobId);
                currentVersionId = row.getLong("ruleset_version_id");
                status = row.getString("status");
                dataSourceId = row.getLong("data_source_id");
                latestVersionId = row.getLong("latest_version_id");
                if (row.wasNull()) throw new IllegalStateException("No published Rule Set version is available");
            }
        }
        if (currentVersionId == latestVersionId) return;
        if (isActiveJobStatus(status)) {
            throw new IllegalStateException("Job version cannot change while it is " + status);
        }
        rebindJob(connection, jobId, latestVersionId, dataSourceId);
        connection.commit();
    }

    private static boolean isActiveJobStatus(String status) {
        return List.of("PLANNING", "PREFLIGHTING", "RUNNING", "RETRYING", "PAUSING",
                "CANCELLING", "VALIDATING").contains(status);
    }

    private static void rebindJob(Connection connection, long jobId, long versionId,
            long dataSourceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE job_event SET job_step_id=NULL WHERE job_id=?")) {
            statement.setLong(1, jobId);
            statement.executeUpdate();
        }
        for (String table : List.of("validation_result", "job_metric")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE job_id=?")) {
                statement.setLong(1, jobId);
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM job_step_attempt WHERE job_step_id IN
                    (SELECT job_step_id FROM job_step WHERE job_id=?)
                """)) {
            statement.setLong(1, jobId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM job_step WHERE job_id=?")) {
            statement.setLong(1, jobId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE anonymization_job
                   SET ruleset_version_id=?, data_source_id=?, status='CREATED',
                       planned_at=NULL, started_at=NULL, ended_at=NULL, heartbeat_at=NULL,
                       error_code=NULL, error_message=NULL
                 WHERE job_id=?
                """)) {
            statement.setLong(1, versionId);
            statement.setLong(2, dataSourceId);
            statement.setLong(3, jobId);
            if (statement.executeUpdate() != 1) throw new SQLException("Job was not rebound");
        }
    }

    private static boolean hasDraft(Connection connection, long rulesetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM ruleset_version WHERE ruleset_id=? AND status='DRAFT'")) {
            statement.setLong(1, rulesetId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1) > 0;
            }
        }
    }

    private static boolean rulesetHasJob(Connection connection, long rulesetId,
            long excludedJobId) throws SQLException {
        String sql = """
                SELECT COUNT(*) FROM anonymization_job j JOIN ruleset_version rv
                  ON rv.ruleset_version_id=j.ruleset_version_id
                 WHERE rv.ruleset_id=?
                """ + (excludedJobId > 0 ? " AND j.job_id<>?" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rulesetId);
            if (excludedJobId > 0) statement.setLong(2, excludedJobId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1) > 0;
            }
        }
    }

    private static boolean repositoryObjectExists(Connection connection, String objectName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM user_objects
                 WHERE object_name=? AND object_type IN ('TABLE','VIEW')
                """)) {
            statement.setString(1, objectName);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1) > 0;
            }
        }
    }

    @Override
    public JobCreationResult createJob(long rulesetId, long versionId,
            String reference, int parallelDegree, String executionMode,
            int chunkSize, long chunkThreshold, String requestedBy) {
        String versionSql = """
                SELECT r.data_source_id, rv.status
                 FROM ruleset r JOIN ruleset_version rv ON rv.ruleset_id = r.ruleset_id
                 WHERE r.ruleset_id = ? AND rv.ruleset_version_id = ?
                """;
        String insert = """
                INSERT INTO anonymization_job (
                    job_reference, ruleset_version_id, data_source_id, status,
                    requested_by, parallel_degree, execution_mode, chunk_size, chunk_threshold)
                VALUES (?, ?, ?, 'CREATED', ?, ?, ?, ?, ?)
                """;
        try (Connection connection = open(false)) {
            try {
                if (!supportsExtendedJobConfiguration(connection)) {
                    throw new IllegalStateException(
                            "Repository migration V2_007 is required before creating a Job");
                }
                long dataSourceId;
                try (PreparedStatement statement = connection.prepareStatement(versionSql)) {
                    statement.setLong(1, rulesetId);
                    statement.setLong(2, versionId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException(
                                "Published ruleset version was not found");
                        if (!"PUBLISHED".equals(row.getString("status"))) {
                            throw new IllegalArgumentException(
                                    "A Job can only use a published ruleset version");
                        }
                        try (PreparedStatement latest = connection.prepareStatement("""
                                SELECT ruleset_version_id FROM ruleset_version
                                 WHERE ruleset_id=? AND status='PUBLISHED'
                                 ORDER BY version_number DESC FETCH FIRST 1 ROW ONLY
                                """)) {
                            latest.setLong(1, rulesetId);
                            try (ResultSet latestRow = latest.executeQuery()) {
                                if (!latestRow.next() || latestRow.getLong(1) != versionId) {
                                    throw new IllegalArgumentException(
                                            "A Job must use the latest published Rule Set version");
                                }
                            }
                        }
                        dataSourceId = row.getLong("data_source_id");
                    }
                }
                long jobId;
                if (rulesetHasJob(connection, rulesetId, 0)) {
                    throw new IllegalStateException(
                            "This Rule Set is already linked to a Job");
                }
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setString(1, reference);
                    statement.setLong(2, versionId);
                    statement.setLong(3, dataSourceId);
                    statement.setString(4, requestedBy);
                    statement.setInt(5, parallelDegree);
                    statement.setString(6, executionMode);
                    statement.setInt(7, chunkSize);
                    statement.setLong(8, chunkThreshold);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT job_id FROM anonymization_job WHERE job_reference = ?")) {
                    statement.setString(1, reference);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new SQLException("Job identifier was not returned");
                        jobId = row.getLong(1);
                    }
                }
                connection.commit();
                return new JobCreationResult(jobId, reference, "CREATED", versionId);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            if (error.getErrorCode() == 1) throw new IllegalArgumentException(
                    "Job reference already exists: " + reference);
            throw failure("create job", error);
        }
    }

    @Override
    public List<RulesetCatalogColumn> listCatalog(long rulesetId, long versionId) {
        String sql = """
                SELECT ct.catalog_table_id, cc.catalog_column_id, cs.schema_name, ct.table_name,
                       cc.column_name, cc.native_data_type,
                       CASE WHEN tr.table_rule_id IS NULL THEN 0 ELSE 1 END table_selected,
                       NVL(ta.code, '') table_algorithm_code,
                       NVL(DBMS_LOB.SUBSTR(tr.filter_expression, 4000, 1), '') table_criteria,
                       CASE WHEN mr.rule_id IS NULL THEN 0 ELSE 1 END selected,
                       NVL(a.code, '') algorithm_code,
                       NVL(DBMS_LOB.SUBSTR(mr.filter_expression, 4000, 1), '') criteria,
                       mr.rule_id
                  FROM ruleset r
                  JOIN ruleset_version rv ON rv.ruleset_id = r.ruleset_id
                  JOIN catalog_schema cs ON cs.data_source_id = r.data_source_id
                  JOIN catalog_table ct ON ct.catalog_schema_id = cs.catalog_schema_id
                  JOIN catalog_column cc ON cc.catalog_table_id = ct.catalog_table_id
                  LEFT JOIN masking_rule mr ON mr.ruleset_version_id = rv.ruleset_version_id
                       AND mr.catalog_column_id = cc.catalog_column_id
                  LEFT JOIN algorithm_version av ON av.algorithm_version_id = mr.algorithm_version_id
                  LEFT JOIN algorithm a ON a.algorithm_id = av.algorithm_id
                  LEFT JOIN table_masking_rule tr ON tr.ruleset_version_id = rv.ruleset_version_id
                       AND tr.catalog_table_id = ct.catalog_table_id
                  LEFT JOIN algorithm_version tav ON tav.algorithm_version_id = tr.algorithm_version_id
                  LEFT JOIN algorithm ta ON ta.algorithm_id = tav.algorithm_id
                 WHERE r.ruleset_id = ? AND rv.ruleset_version_id = ?
                   AND ct.synchronized_at >= cs.synchronized_at
                   AND cc.synchronized_at >= cs.synchronized_at
                 ORDER BY cs.schema_name, ct.table_name, cc.ordinal_position, cc.column_name
                """;
        List<RulesetCatalogColumn> result = new ArrayList<>();
        try (Connection connection = open(true);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            List<AlgorithmCompatibility> algorithms = loadCompatibleAlgorithms(
                    connection, rulesetId);
            statement.setLong(1, rulesetId);
            statement.setLong(2, versionId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(new RulesetCatalogColumn(
                        row.getLong("catalog_table_id"), row.getLong("catalog_column_id"),
                        value(row, "schema_name"), value(row, "table_name"),
                        value(row, "column_name"), value(row, "native_data_type"),
                        row.getInt("table_selected") == 1, value(row, "table_algorithm_code"),
                        value(row, "table_criteria"), row.getInt("selected") == 1,
                        value(row, "algorithm_code"), value(row, "criteria"),
                        configuredParameters(connection, row.getLong("rule_id")),
                        compatibleAlgorithmCodes(algorithms,
                                value(row, "native_data_type"))));
            }
            return result;
        } catch (SQLException error) {
            throw failure("load ruleset catalog", error);
        }
    }

    @Override
    public int saveInventory(long rulesetId, long versionId,
            List<RulesetInventorySelection> selections) {
        Objects.requireNonNull(selections, "selections");
        String versionSql = """
                SELECT rv.status, r.data_source_id FROM ruleset_version rv
                  JOIN ruleset r ON r.ruleset_id = rv.ruleset_id
                 WHERE r.ruleset_id = ? AND rv.ruleset_version_id = ? FOR UPDATE
                """;
        String resolveColumnSql = """
                SELECT cc.catalog_column_id, ct.catalog_table_id, av.algorithm_version_id,
                       ai.implementation_type, cc.native_data_type, a.category
                  FROM catalog_column cc JOIN catalog_table ct ON ct.catalog_table_id=cc.catalog_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                  JOIN data_source ds ON ds.data_source_id=cs.data_source_id
                  JOIN connector co ON co.connector_id=ds.connector_id
                  JOIN algorithm a ON a.code=? AND a.status='ACTIVE' AND a.category<>'STRUCTURAL'
                  JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id AND av.status='PUBLISHED'
                  JOIN algorithm_implementation ai ON ai.algorithm_version_id=av.algorithm_version_id
                       AND ai.technology_code=co.technology_code AND ai.status='PUBLISHED'
                 WHERE cc.catalog_column_id=? AND cs.data_source_id=?
                   AND NVL(cc.primary_key_member, 'N') <> 'Y'
                ORDER BY av.version_number DESC FETCH FIRST 1 ROW ONLY
                """;
        String resolveTableSql = """
                SELECT ct.catalog_table_id, av.algorithm_version_id
                  FROM catalog_table ct
                  JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                  JOIN algorithm a ON a.code=? AND a.status='ACTIVE' AND a.category='STRUCTURAL'
                  JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
                       AND av.status IN ('PUBLISHED','DRAFT')
                 WHERE ct.catalog_table_id=? AND cs.data_source_id=?
                 ORDER BY CASE av.status WHEN 'PUBLISHED' THEN 0 ELSE 1 END,
                          av.version_number DESC FETCH FIRST 1 ROW ONLY
                """;
        String insertColumnSql = "INSERT INTO masking_rule(ruleset_version_id,catalog_column_id,algorithm_version_id,execution_order,filter_expression,enabled) VALUES(?,?,?,?,?, 'Y')";
        String insertTableSql = "INSERT INTO table_masking_rule(ruleset_version_id,catalog_table_id,algorithm_version_id,execution_order,filter_expression,enabled) VALUES(?,?,?,?,?, 'Y')";
        try (Connection connection = open(false)) {
            try {
                long dataSourceId;
                try (PreparedStatement statement = connection.prepareStatement(versionSql)) {
                    statement.setLong(1, rulesetId); statement.setLong(2, versionId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Ruleset version was not found");
                        if (!"DRAFT".equals(row.getString("status")))
                            throw new IllegalArgumentException("Only a DRAFT version can be edited");
                        dataSourceId = row.getLong("data_source_id");
                    }
                }
                List<ResolvedInventorySelection> resolved = new ArrayList<>();
                java.util.Set<Long> destructiveTables = new java.util.HashSet<>();
                try (PreparedStatement columnStatement = connection.prepareStatement(resolveColumnSql);
                        PreparedStatement tableStatement = connection.prepareStatement(resolveTableSql)) {
                    for (RulesetInventorySelection selection : selections) {
                        PreparedStatement statement = "TABLE".equals(selection.targetType())
                                ? tableStatement : columnStatement;
                        statement.setString(1, selection.algorithmCode());
                        statement.setLong(2, selection.targetId());
                        statement.setLong(3, dataSourceId);
                        try (ResultSet row = statement.executeQuery()) {
                            if (!row.next()) throw new IllegalArgumentException(
                                    selection.targetType() + " or published algorithm is invalid: "
                                            + selection.targetId());
                            long tableId = "TABLE".equals(selection.targetType())
                                    ? row.getLong(1) : row.getLong(2);
                            long algorithmId = "TABLE".equals(selection.targetType())
                                    ? row.getLong(2) : row.getLong(3);
                            if ("COLUMN".equals(selection.targetType())
                                    && !isAlgorithmCompatible(selection.algorithmCode(),
                                            row.getString("category"),
                                            row.getString("implementation_type"),
                                            row.getString("native_data_type"))) {
                                throw new IllegalArgumentException("Algorithm "
                                        + selection.algorithmCode() + " is not compatible with "
                                        + row.getString("native_data_type") + " column "
                                        + selection.targetId());
                            }
                            if ("TABLE".equals(selection.targetType())) {
                                if (!destructiveTables.add(tableId)) throw new IllegalArgumentException(
                                        "Only one structural algorithm can be selected per table");
                            } else if("LOOKUP_MAPPING".equals(row.getString(4))){try(PreparedStatement check=connection.prepareStatement("SELECT 1 FROM algorithm_lookup_config c JOIN lookup_dataset d ON d.lookup_dataset_id=c.lookup_dataset_id WHERE c.algorithm_version_id=? AND d.status='PUBLISHED' AND EXISTS(SELECT 1 FROM lookup_dataset_value v WHERE v.lookup_dataset_id=d.lookup_dataset_id AND v.enabled='Y')")){check.setLong(1,algorithmId);try(ResultSet found=check.executeQuery()){if(!found.next())throw new IllegalArgumentException("Lookup algorithm has no published non-empty dataset: "+selection.algorithmCode());}}}
                            if ("TRUNCATE_TABLE".equals(selection.algorithmCode())
                                    && !selection.criteria().isBlank()) {
                                throw new IllegalArgumentException(
                                        "TRUNCATE_TABLE always applies to all rows and cannot have criteria");
                            }
                            Map<String, String> parameters = "COLUMN".equals(selection.targetType())
                                    ? resolveParameters(connection, versionId, selection.targetId(),
                                            algorithmId, selection.parameters())
                                    : Map.of();
                            resolved.add(new ResolvedInventorySelection(selection.targetType(),
                                    selection.targetId(), tableId, algorithmId,
                                    selection.algorithmCode(), selection.criteria(), parameters));
                        }
                    }
                }
                for (ResolvedInventorySelection item : resolved) {
                    if ("COLUMN".equals(item.targetType()) && destructiveTables.contains(item.tableId())) {
                        throw new IllegalArgumentException(
                                "Column algorithms are obsolete when a structural table algorithm is selected");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM masking_rule WHERE ruleset_version_id=?")) {
                    statement.setLong(1, versionId); statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM table_masking_rule WHERE ruleset_version_id=?")) {
                    statement.setLong(1, versionId); statement.executeUpdate();
                }
                try (PreparedStatement columnStatement = connection.prepareStatement(insertColumnSql);
                        PreparedStatement tableStatement = connection.prepareStatement(insertTableSql)) {
                    int order = 10;
                    for (ResolvedInventorySelection item : resolved) {
                        PreparedStatement statement = "TABLE".equals(item.targetType())
                                ? tableStatement : columnStatement;
                        statement.setLong(1, versionId); statement.setLong(2, item.targetId());
                        statement.setLong(3, item.algorithmId());statement.setInt(4, order);
                        if (item.criteria().isBlank()) statement.setNull(5, java.sql.Types.CLOB);
                        else statement.setString(5, item.criteria());
                        order += 10;
                        statement.addBatch();
                    }
                    columnStatement.executeBatch();
                    tableStatement.executeBatch();
                }
                for (ResolvedInventorySelection item : resolved) {
                    if (!"COLUMN".equals(item.targetType()) || item.parameters().isEmpty()) continue;
                    long ruleId;
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT rule_id FROM masking_rule WHERE ruleset_version_id=? AND catalog_column_id=?")) {
                        statement.setLong(1, versionId);
                        statement.setLong(2, item.targetId());
                        try (ResultSet row = statement.executeQuery()) {
                            if (!row.next()) throw new SQLException("Unable to reload saved masking rule");
                            ruleId = row.getLong(1);
                        }
                    }
                    insertRuleParameters(connection, ruleId, item.algorithmId(), item.parameters());
                }
                connection.commit();
                return resolved.size();
            } catch (SQLException | RuntimeException error) {
                connection.rollback(); throw error;
            }
        } catch (SQLException error) {
            throw failure("save ruleset inventory", error);
        }
    }

    private record ResolvedInventorySelection(String targetType, long targetId,
            long tableId, long algorithmId, String algorithmCode, String criteria,
            Map<String, String> parameters) { }

    private static List<AlgorithmCompatibility> loadCompatibleAlgorithms(
            Connection connection, long rulesetId) throws SQLException {
        String sql = """
                SELECT DISTINCT a.code,a.category,ai.implementation_type,c.technology_code
                  FROM ruleset r JOIN data_source ds ON ds.data_source_id=r.data_source_id
                  JOIN connector c ON c.connector_id=ds.connector_id
                  JOIN algorithm a ON a.status='ACTIVE' AND a.category<>'STRUCTURAL'
                  JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
                       AND av.status='PUBLISHED'
                  JOIN algorithm_implementation ai ON ai.algorithm_version_id=av.algorithm_version_id
                       AND ai.technology_code=c.technology_code AND ai.status='PUBLISHED'
                 WHERE r.ruleset_id=? ORDER BY a.code
                """;
        List<AlgorithmCompatibility> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rulesetId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(new AlgorithmCompatibility(row.getString(1),
                        row.getString(2), row.getString(3), row.getString(4)));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> compatibleAlgorithmCodes(
            List<AlgorithmCompatibility> algorithms, String dataType) {
        return algorithms.stream()
                .filter(item -> isAlgorithmCompatible(item.code(), item.category(),
                        item.implementationType(), dataType, item.technologyCode()))
                .map(AlgorithmCompatibility::code).toList();
    }

    static boolean isAlgorithmCompatible(String code, String category,
            String implementationType, String nativeDataType) {
        return isAlgorithmCompatible(code, category, implementationType, nativeDataType, "");
    }

    static boolean isAlgorithmCompatible(String code, String category,
            String implementationType, String nativeDataType, String technologyCode) {
        String algorithm = code == null ? "" : code.toUpperCase(java.util.Locale.ROOT);
        if ("NULLIFY".equals(algorithm)) return true;
        DataTypeFamily family = dataTypeFamily(nativeDataType);
        if ("JSON_PATH_MASK".equals(algorithm)) {
            return isJsonCompatible(nativeDataType, technologyCode);
        }
        if ("IBAN_GENERATOR".equals(algorithm)
                || "PERSISTENT_MAPPING".equals(algorithm)
                || "GEOGRAPHIC_COHERENT_ADDRESS".equals(algorithm)) {
            return family == DataTypeFamily.TEXT;
        }
        if ("LOOKUP_MAPPING".equalsIgnoreCase(implementationType)) {
            return family == DataTypeFamily.TEXT;
        }
        if (algorithm.equals("ZERO_NUMBER") || algorithm.equals("PERTURB_NUMBER_PERCENT")) {
            return family == DataTypeFamily.NUMERIC;
        }
        if (algorithm.equals("GENERALIZE_DATE_YEAR")
                || algorithm.equals("GENERALIZE_DATE_MONTH")
                || algorithm.equals("DATE_SHIFT_DETERMINISTIC")) {
            return family == DataTypeFamily.TEMPORAL;
        }
        String kind = category == null ? "" : category.toUpperCase(java.util.Locale.ROOT);
        return switch (kind) {
            case "PERTURBATION" -> family == DataTypeFamily.NUMERIC;
            case "GENERALIZATION" -> family == DataTypeFamily.TEMPORAL;
            case "HASHING", "MASKING", "RANDOMIZATION", "SYNTHETIC", "SUBSTITUTION",
                 "TOKENIZATION" ->
                    family == DataTypeFamily.TEXT;
            case "SUPPRESSION" -> false;
            default -> false;
        };
    }

    private static DataTypeFamily dataTypeFamily(String nativeDataType) {
        String type = nativeDataType == null ? ""
                : nativeDataType.toUpperCase(java.util.Locale.ROOT)
                        .replaceAll("\\s*\\(.*$", "").trim();
        if (type.matches(".*(RAW|BLOB|BINARY|VARBINARY|BYTEA|IMAGE).*"))
            return DataTypeFamily.OTHER;
        if (type.matches(".*(CHAR|VARCHAR|VARCHAR2|NVARCHAR|NVARCHAR2|TEXT|CLOB|NCLOB|CITEXT|LONG).*"))
            return DataTypeFamily.TEXT;
        if (type.matches("(NUMBER|NUMERIC|DECIMAL|DEC|INTEGER|INT|INT2|INT4|INT8|SMALLINT|BIGINT|TINYINT|FLOAT|FLOAT4|FLOAT8|REAL|DOUBLE|DOUBLE PRECISION|BINARY_FLOAT|BINARY_DOUBLE|MONEY|SMALLMONEY|SERIAL|BIGSERIAL)"))
            return DataTypeFamily.NUMERIC;
        if (type.matches("(DATE|DATETIME|DATETIME2|SMALLDATETIME|DATETIMEOFFSET|TIMESTAMP|TIMESTAMP WITHOUT TIME ZONE|TIMESTAMP WITH TIME ZONE|TIMESTAMP WITH LOCAL TIME ZONE|TIMESTAMPTZ)"))
            return DataTypeFamily.TEMPORAL;
        return DataTypeFamily.OTHER;
    }

    private static boolean isJsonCompatible(String nativeDataType, String technologyCode) {
        String type = nativeDataType == null ? ""
                : nativeDataType.toUpperCase(java.util.Locale.ROOT)
                        .replaceAll("\\s*\\(.*$", "").trim();
        String technology = technologyCode == null ? ""
                : technologyCode.toUpperCase(java.util.Locale.ROOT);
        return switch (technology) {
            case "POSTGRESQL" -> type.equals("JSONB");
            case "SQLSERVER" -> type.matches("(VARCHAR|NVARCHAR|TEXT)");
            case "ORACLE" -> type.matches("(JSON|VARCHAR2|NVARCHAR2|CLOB|NCLOB)");
            default -> type.matches("(JSON|JSONB|VARCHAR|VARCHAR2|NVARCHAR|NVARCHAR2|TEXT|CLOB|NCLOB)");
        };
    }

    private enum DataTypeFamily { TEXT, NUMERIC, TEMPORAL, OTHER }
    private record AlgorithmCompatibility(String code, String category,
            String implementationType, String technologyCode) { }

    private static List<String> configuredParameters(Connection connection, long ruleId)
            throws SQLException {
        if (ruleId <= 0) return List.of();
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT apd.parameter_name FROM rule_parameter rp
                  JOIN algorithm_parameter_def apd
                    ON apd.parameter_definition_id=rp.parameter_definition_id
                 WHERE rp.rule_id=? ORDER BY apd.parameter_name
                """)) {
            statement.setLong(1, ruleId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(row.getString(1));
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, String> resolveParameters(Connection connection, long versionId,
            long columnId, long algorithmId, Map<String, String> requested) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        String sql = """
                SELECT apd.parameter_name, apd.required, apd.default_value,
                       DBMS_LOB.SUBSTR(rp.parameter_value,4000,1) existing_value
                  FROM algorithm_parameter_def apd
                  LEFT JOIN masking_rule mr ON mr.ruleset_version_id=?
                       AND mr.catalog_column_id=? AND mr.algorithm_version_id=?
                  LEFT JOIN rule_parameter rp ON rp.rule_id=mr.rule_id
                       AND rp.parameter_definition_id=apd.parameter_definition_id
                 WHERE apd.algorithm_version_id=? ORDER BY apd.parameter_name
                """;
        java.util.Set<String> known = new java.util.HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, versionId);
            statement.setLong(2, columnId);
            statement.setLong(3, algorithmId);
            statement.setLong(4, algorithmId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    String name = row.getString("parameter_name");
                    known.add(name);
                    String value = requested.get(name);
                    if (KEEP_PARAMETER_VALUE.equals(value)) value = row.getString("existing_value");
                    if (value != null && !value.isBlank()) result.put(name, value);
                    else if ("Y".equals(row.getString("required"))
                            && (row.getString("default_value") == null
                                || row.getString("default_value").isBlank())) {
                        throw new IllegalArgumentException(
                                "Required parameter is missing: " + name);
                    }
                }
            }
        }
        for (String name : requested.keySet()) {
            if (!known.contains(name)) throw new IllegalArgumentException(
                    "Unknown parameter for selected algorithm: " + name);
        }
        return Map.copyOf(result);
    }

    private static void insertRuleParameters(Connection connection, long ruleId,
            long algorithmId, Map<String, String> parameters) throws SQLException {
        String sql = """
                INSERT INTO rule_parameter(rule_id,parameter_definition_id,parameter_value)
                SELECT ?,parameter_definition_id,? FROM algorithm_parameter_def
                 WHERE algorithm_version_id=? AND parameter_name=?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, String> parameter : parameters.entrySet()) {
                statement.setLong(1, ruleId);
                statement.setString(2, parameter.getValue());
                statement.setLong(3, algorithmId);
                statement.setString(4, parameter.getKey());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private RulesetSummary loadSummary(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SUMMARY_SELECT + " WHERE r.ruleset_id = ?")) {
            statement.setLong(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Ruleset was not found");
                return summary(row);
            }
        }
    }

    private static List<RulesetVersionView> loadVersions(Connection connection, long rulesetId)
            throws SQLException {
        String sql = """
                SELECT rv.ruleset_version_id, rv.version_number, rv.status,
                       TO_CHAR(rv.created_at, 'YYYY-MM-DD"T"HH24:MI:SS.FF3TZH:TZM') created_at,
                       TO_CHAR(rv.published_at, 'YYYY-MM-DD"T"HH24:MI:SS.FF3TZH:TZM') published_at,
                       ((SELECT COUNT(*) FROM masking_rule mr
                         WHERE mr.ruleset_version_id = rv.ruleset_version_id) +
                        (SELECT COUNT(*) FROM table_masking_rule tr
                         WHERE tr.ruleset_version_id = rv.ruleset_version_id)) inventory_count,
                       (SELECT COUNT(*) FROM anonymization_job j
                         WHERE j.ruleset_version_id = rv.ruleset_version_id) job_count
                  FROM ruleset_version rv
                 WHERE rv.ruleset_id = ?
                 ORDER BY rv.version_number DESC
                """;
        List<RulesetVersionView> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rulesetId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(new RulesetVersionView(
                        row.getLong("ruleset_version_id"), row.getInt("version_number"),
                        value(row, "status"), value(row, "created_at"),
                        value(row, "published_at"), row.getInt("inventory_count"),
                        row.getInt("job_count")));
            }
        }
        return result;
    }

    private static List<RulesetInventoryEntry> loadInventory(Connection connection, long versionId)
            throws SQLException {
        String sql = """
                SELECT mr.rule_id, cs.schema_name, ct.table_name, cc.column_name,
                       cc.native_data_type, a.code algorithm_code, mr.enabled,
                       NVL(DBMS_LOB.SUBSTR(mr.filter_expression, 4000, 1), 'ALL') criteria
                  FROM masking_rule mr
                  JOIN catalog_column cc ON cc.catalog_column_id = mr.catalog_column_id
                  JOIN catalog_table ct ON ct.catalog_table_id = cc.catalog_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id = ct.catalog_schema_id
                  JOIN algorithm_version av ON av.algorithm_version_id = mr.algorithm_version_id
                  JOIN algorithm a ON a.algorithm_id = av.algorithm_id
                 WHERE mr.ruleset_version_id = ?
                UNION ALL
                SELECT -tr.table_rule_id rule_id, cs.schema_name, ct.table_name,
                       '[TABLE]' column_name, 'TABLE' native_data_type,
                       a.code algorithm_code, tr.enabled,
                       NVL(DBMS_LOB.SUBSTR(tr.filter_expression, 4000, 1), 'ALL') criteria
                  FROM table_masking_rule tr
                  JOIN catalog_table ct ON ct.catalog_table_id = tr.catalog_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id = ct.catalog_schema_id
                  JOIN algorithm_version av ON av.algorithm_version_id = tr.algorithm_version_id
                  JOIN algorithm a ON a.algorithm_id = av.algorithm_id
                 WHERE tr.ruleset_version_id = ?
                 ORDER BY 2, 3, 4
                """;
        List<RulesetInventoryEntry> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, versionId);
            statement.setLong(2, versionId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(new RulesetInventoryEntry(row.getLong("rule_id"),
                        value(row, "schema_name"), value(row, "table_name"),
                        value(row, "column_name"), value(row, "native_data_type"),
                        value(row, "algorithm_code"), "Y".equals(value(row, "enabled")),
                        value(row, "criteria")));
            }
        }
        return result;
    }

    private static List<RulesetJobView> loadJobs(Connection connection, long rulesetId)
            throws SQLException {
        String sql = """
                SELECT j.job_id, j.job_reference, j.status,
                       (SELECT MAX(latest.version_number)
                          FROM ruleset_version latest
                         WHERE latest.ruleset_id=rv.ruleset_id
                           AND latest.status='PUBLISHED') version_number,
                       TO_CHAR(j.created_at, 'YYYY-MM-DD"T"HH24:MI:SS.FF3TZH:TZM') created_at,
                       TO_CHAR(j.planned_at, 'YYYY-MM-DD"T"HH24:MI:SS.FF3TZH:TZM') planned_at
                  FROM anonymization_job j JOIN ruleset_version rv
                    ON rv.ruleset_version_id = j.ruleset_version_id
                 WHERE rv.ruleset_id = ? ORDER BY j.created_at DESC
                """;
        List<RulesetJobView> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rulesetId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(new RulesetJobView(row.getLong("job_id"),
                        value(row, "job_reference"), value(row, "status"),
                        row.getInt("version_number"), value(row, "created_at"),
                        value(row, "planned_at")));
            }
        }
        return result;
    }

    @Override
    public RulesetSqlExport exportSql(long rulesetId, long versionId) {
        String metadata = """
                SELECT r.code,rv.version_number,c.technology_code
                  FROM ruleset r JOIN ruleset_version rv ON rv.ruleset_id=r.ruleset_id
                  JOIN data_source d ON d.data_source_id=r.data_source_id
                  JOIN connector c ON c.connector_id=d.connector_id
                 WHERE r.ruleset_id=? AND rv.ruleset_version_id=?
                """;
        try (Connection connection = open(true)) {
            String code, technology;
            int versionNumber;
            try (PreparedStatement statement = connection.prepareStatement(metadata)) {
                statement.setLong(1, rulesetId);
                statement.setLong(2, versionId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException(
                            "Rule Set version was not found");
                    code = row.getString(1);
                    versionNumber = row.getInt(2);
                    technology = row.getString(3);
                }
            }
            List<RepositoryTablePlan> plans = rulesetPlans.loadRuleset(connection,
                    rulesetId, versionId, technology);
            if (plans.stream().flatMap(plan -> plan.assignments().stream())
                    .anyMatch(assignment -> assignment.parameterDefinitions().stream()
                            .anyMatch(parameter -> "key_material".equals(parameter.name())))) {
                throw new IllegalStateException("SQL export is disabled for keyed algorithms "
                        + "to prevent secret disclosure. Execute this Rule Set through Privonym.");
            }
            StringBuilder sql = new StringBuilder()
                    .append("-- Privonym Rule Set SQL export\n")
                    .append("-- Rule Set: ").append(code).append("\n")
                    .append("-- Version: ").append(versionNumber).append("\n")
                    .append("-- Target technology: ").append(technology).append("\n")
                    .append("-- Generated only; no statement has been executed.\n\n");
            appendDialectPreamble(sql, technology);
            Map<Long, ExportLookup> exportLookups = collectExportLookups(plans);
            appendLookupSetup(sql, technology, exportLookups);
            int statementCount = 0;
            for (RepositoryTablePlan plan : plans) {
                String statementSql;
                if (!plan.tableAlgorithmCode().isBlank()) {
                    statementSql = switch (plan.tableAlgorithmCode()) {
                        case "DELETE_TABLE_ROWS" -> "DELETE FROM " + plan.table().toSql()
                                + (plan.filterExpression().isBlank() ? ""
                                        : " WHERE " + plan.filterExpression());
                        case "TRUNCATE_TABLE" -> {
                            if (!plan.filterExpression().isBlank()) throw new IllegalStateException(
                                    "TRUNCATE_TABLE cannot have a row criterion");
                            yield "TRUNCATE TABLE " + plan.table().toSql();
                        }
                        default -> throw new IllegalStateException(
                                "Unsupported table algorithm: " + plan.tableAlgorithmCode());
                    };
                } else {
                    GeneratedOracleStatement generated = updateGenerator.generate(
                            plan.table(), exportAssignments(plan.assignments(), technology,
                                    exportLookups));
                    statementSql = generated.sql() + (plan.filterExpression().isBlank() ? ""
                            : "\nWHERE " + plan.filterExpression());
                }
                sql.append("-- Table: ").append(plan.table().toSql()).append("\n")
                        .append(statementSql).append(";\n\n");
                statementCount++;
            }
            appendLookupCleanup(sql, technology, exportLookups);
            appendDialectCommit(sql, technology);
            return new RulesetSqlExport(code, versionNumber, technology,
                    statementCount, sql.toString());
        } catch (SQLException error) {
            throw failure("generate Rule Set SQL", error);
        }
    }

    private static final int INLINE_LOOKUP_LIMIT = 100;
    private static final int LOOKUP_INSERT_BATCH_SIZE = 500;

    private static void appendDialectPreamble(StringBuilder sql, String technology) {
        switch (technology) {
            case "ORACLE" -> sql.append("-- Dialect: Oracle SQL\n")
                    .append("SET DEFINE OFF;\n\n");
            case "POSTGRESQL" -> sql.append("-- Dialect: PostgreSQL SQL\n")
                    .append("BEGIN;\n\n");
            case "SQLSERVER" -> sql.append("-- Dialect: Microsoft Transact-SQL\n")
                    .append("SET QUOTED_IDENTIFIER ON;\n")
                    .append("SET XACT_ABORT ON;\n")
                    .append("SET NOCOUNT ON;\n")
                    .append("BEGIN TRANSACTION;\n\n");
            default -> throw new IllegalArgumentException(
                    "Unsupported target technology for SQL export: " + technology);
        }
    }

    private static void appendDialectCommit(StringBuilder sql, String technology) {
        if ("SQLSERVER".equals(technology)) sql.append("COMMIT TRANSACTION;\n");
        else sql.append("COMMIT;\n");
    }

    private static Map<Long, ExportLookup> collectExportLookups(List<RepositoryTablePlan> plans) {
        Map<Long, ExportLookup> lookups = new LinkedHashMap<>();
        for (RepositoryTablePlan plan : plans) {
            for (com.privonym.adapter.oracle.OracleMaskingAssignment assignment
                    : plan.assignments()) {
                if (assignment.lookupDatasetId() != null
                        && assignment.lookupValues().size() > INLINE_LOOKUP_LIMIT) {
                    long id = assignment.lookupDatasetId();
                    lookups.computeIfAbsent(id, ignored -> new ExportLookup(
                            "PV_LKP_" + id, assignment.lookupValues()));
                }
            }
        }
        return lookups;
    }

    private static List<com.privonym.adapter.oracle.OracleMaskingAssignment> exportAssignments(
            List<com.privonym.adapter.oracle.OracleMaskingAssignment> assignments,
            String technology, Map<Long, ExportLookup> lookups) {
        List<com.privonym.adapter.oracle.OracleMaskingAssignment> result = new ArrayList<>();
        for (com.privonym.adapter.oracle.OracleMaskingAssignment assignment : assignments) {
            ExportLookup lookup = assignment.lookupDatasetId() == null ? null
                    : lookups.get(assignment.lookupDatasetId());
            if (lookup == null) {
                result.add(assignment);
                continue;
            }
            int size = lookup.values().size();
            String table = lookupTableName(technology, lookup.name());
            String hashIndex = switch (technology) {
                case "POSTGRESQL" -> "MOD(ABS(CAST(HASHTEXT(CAST(${column} AS TEXT)) "
                        + "AS BIGINT)), "
                        + size + ")";
                case "SQLSERVER" -> "ABS(CONVERT(bigint, CHECKSUM(CONVERT(nvarchar(max), "
                        + "${column})))) % " + size;
                default -> "MOD(ORA_HASH(TO_CHAR(${column})), " + size + ")";
            };
            String template = "CASE WHEN ${column} IS NULL THEN NULL ELSE "
                    + "(SELECT value_text FROM " + table
                    + " WHERE lookup_index = " + hashIndex + ") END";
            result.add(new com.privonym.adapter.oracle.OracleMaskingAssignment(
                    assignment.columnName(), assignment.algorithmCode(),
                    assignment.implementationType(), template, assignment.executionOrder(),
                    assignment.parameterDefinitions(), assignment.parameterValues(),
                    assignment.lookupDatasetId(), assignment.lookupValues()));
        }
        return List.copyOf(result);
    }

    private static void appendLookupSetup(StringBuilder sql, String technology,
            Map<Long, ExportLookup> lookups) {
        if (lookups.isEmpty()) return;
        sql.append("-- Large lookup datasets are staged once and accessed by indexed hash.\n")
                .append("-- This avoids generating thousands of CASE/WHEN branches.\n\n");
        for (ExportLookup lookup : lookups.values()) {
            String table = lookupTableName(technology, lookup.name());
            switch (technology) {
                case "POSTGRESQL" -> sql.append("CREATE TEMP TABLE ").append(table)
                        .append(" (lookup_index integer PRIMARY KEY, value_text text NOT NULL) ")
                        .append("ON COMMIT PRESERVE ROWS;\n");
                case "SQLSERVER" -> sql.append("CREATE TABLE ").append(table)
                        .append(" (lookup_index int PRIMARY KEY, ")
                        .append("value_text nvarchar(4000) NOT NULL);\n");
                default -> sql.append("CREATE GLOBAL TEMPORARY TABLE ").append(table)
                        .append(" (lookup_index NUMBER(10) PRIMARY KEY, ")
                        .append("value_text VARCHAR2(4000) NOT NULL) ON COMMIT PRESERVE ROWS;\n");
            }
            appendLookupValues(sql, technology, table, lookup.values());
            sql.append('\n');
        }
    }

    private static void appendLookupValues(StringBuilder sql, String technology, String table,
            List<String> values) {
        for (int start = 0; start < values.size(); start += LOOKUP_INSERT_BATCH_SIZE) {
            int end = Math.min(start + LOOKUP_INSERT_BATCH_SIZE, values.size());
            if ("ORACLE".equals(technology)) {
                sql.append("INSERT ALL\n");
                for (int index = start; index < end; index++) {
                    sql.append("  INTO ").append(table)
                            .append(" (lookup_index, value_text) VALUES (")
                            .append(index).append(", '").append(sqlLiteral(values.get(index)))
                            .append("')\n");
                }
                sql.append("SELECT 1 FROM dual;\n");
            } else {
                sql.append("INSERT INTO ").append(table)
                        .append(" (lookup_index, value_text) VALUES\n");
                for (int index = start; index < end; index++) {
                    sql.append("  (").append(index)
                            .append("SQLSERVER".equals(technology) ? ", N'" : ", '")
                            .append(sqlLiteral(values.get(index))).append("')")
                            .append(index + 1 < end ? ",\n" : ";\n");
                }
            }
        }
    }

    private static void appendLookupCleanup(StringBuilder sql, String technology,
            Map<Long, ExportLookup> lookups) {
        if (lookups.isEmpty()) return;
        sql.append("-- Remove lookup staging objects.\n");
        for (ExportLookup lookup : lookups.values()) {
            sql.append("DROP TABLE ").append(lookupTableName(technology, lookup.name()))
                    .append(";\n");
        }
        sql.append('\n');
    }

    private static String lookupTableName(String technology, String baseName) {
        if ("SQLSERVER".equals(technology)) return "#" + baseName;
        return baseName;
    }

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private record ExportLookup(String name, List<String> values) {
        private ExportLookup {
            values = List.copyOf(values);
        }
    }

    private Connection open(boolean readOnly) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, username, new String(password));
        connection.setReadOnly(readOnly);
        connection.setAutoCommit(false);
        return connection;
    }

    private static RulesetSummary summary(ResultSet row) throws SQLException {
        return new RulesetSummary(row.getLong("ruleset_id"), value(row, "code"),
                value(row, "name"), value(row, "description"), row.getLong("data_source_id"),
                value(row, "data_source_name"), value(row, "technology_code"),
                value(row, "environment_code"),
                value(row, "environment_name"), row.getInt("version_count"),
                row.getInt("inventory_count"), row.getInt("job_count"));
    }

    private static String value(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? "" : value;
    }

    private static IllegalStateException failure(String action, SQLException error) {
        return new IllegalStateException("Unable to " + action + ": " + error.getMessage(), error);
    }

    private record JobExecutionSettings(int parallelDegree, String executionMode,
            int chunkSize, long chunkThreshold) { }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }
}
