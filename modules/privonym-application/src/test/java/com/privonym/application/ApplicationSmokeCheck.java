package com.privonym.application;

import com.privonym.application.port.out.JobRepository;
import com.privonym.application.port.out.ExecutionJournal;
import com.privonym.application.port.out.ValidationJournal;
import com.privonym.application.port.out.JobReportRepository;
import com.privonym.application.service.ApplicationAuditEvent;
import com.privonym.application.service.GeneratedPlan;
import com.privonym.application.service.GeneratedPlanStep;
import com.privonym.application.service.PlanJobCommand;
import com.privonym.application.service.PlanJobResult;
import com.privonym.application.service.PlanJobService;
import com.privonym.application.service.PreflightJobCommand;
import com.privonym.application.service.PreflightJobResult;
import com.privonym.application.service.PreflightJobService;
import com.privonym.application.service.ExecuteJobCommand;
import com.privonym.application.service.ExecuteJobResult;
import com.privonym.application.service.ExecuteJobService;
import com.privonym.application.service.JobExecutionCancelledException;
import com.privonym.application.service.ExecutablePlan;
import com.privonym.application.service.ExecutableStep;
import com.privonym.application.service.ExecutionOutcome;
import com.privonym.application.service.StepExecutionResult;
import com.privonym.application.service.ValidateJobCommand;
import com.privonym.application.service.ValidateJobService;
import com.privonym.application.service.ValidationMetric;
import com.privonym.application.service.ValidationOutcome;
import com.privonym.application.service.GenerateReportCommand;
import com.privonym.application.service.GenerateReportService;
import com.privonym.application.service.JobReport;
import com.privonym.application.service.OracleSqlInventoryPreviewService;
import com.privonym.application.service.SqlServerSqlInventoryPreviewService;
import com.privonym.application.service.PostgresqlSqlInventoryPreviewService;
import com.privonym.application.service.MultiDialectSqlInventoryPreviewService;
import com.privonym.application.service.SqlInventoryPreview;
import com.privonym.application.service.CreateDraftInventoryService;
import com.privonym.application.service.DraftInventoryResult;
import com.privonym.domain.execution.ExecutionMode;
import com.privonym.domain.execution.RecoveryMode;
import com.privonym.domain.catalog.DataSourceId;
import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.JobId;
import com.privonym.domain.job.JobReference;
import com.privonym.domain.job.JobStatus;
import com.privonym.domain.job.PlanChecksum;
import com.privonym.domain.policy.RulesetVersionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ApplicationSmokeCheck {
    private static final PlanChecksum CHECKSUM = new PlanChecksum(
            "7c10d88ca9b5225fdb6208259713c854369fa5efbcb151854432ae76f1fe57a2");

    private ApplicationSmokeCheck() {
    }

    public static void main(String[] args) {
        verifiesSuccessfulPlanning();
        verifiesFailedPlanning();
        verifiesSuccessfulPreflight();
        verifiesSuccessfulDirectExecution();
        verifiesCancelledExecution();
        verifiesValidationAndReport();
        verifiesOracleSqlInventoryPreview();
        ExactOracleImportSmokeCheck.verify();
        verifiesSqlServerSqlInventoryPreview();
        verifiesPostgresqlSqlInventoryPreview();
        verifiesMultiDialectTransformerFoundation();
        verifiesDraftInventoryCreation();
        System.out.println("Privonym application smoke check passed.");
    }

    private static void verifiesOracleSqlInventoryPreview() {
        String ddl = """
                UPDATE FORMATION.CUSTOMER
                   SET EMAIL = CASE WHEN EMAIL IS NULL THEN NULL
                                    ELSE REGEXP_REPLACE(EMAIL, '[[:alnum:]]', 'X') END,
                       CREATED_AT = NULL;
                TRUNCATE TABLE FORMATION.CUSTOMER_STAGE;
                """;
        SqlInventoryPreview preview = new OracleSqlInventoryPreviewService().preview("ORACLE", ddl);
        assertEquals(2, preview.statementCount(), "masking statement count");
        assertEquals(2, preview.tables().size(), "detected operation count");
        assertEquals("CUSTOMER", preview.tables().get(0).name(), "detected table");
        assertEquals(2, preview.columnCount(), "detected column rules");
        assertEquals("ORACLE_EXPRESSION_EXACT", preview.tables().get(0).columns().get(0).algorithmCode(),
                "detected masking algorithm");
        assertEquals(preview.tables().get(0).columns().get(0).sourceExpression(), preview.tables().get(0).columns().get(0).parameters()
                .get("expression"), "original scalar expression preserved");
        assertEquals("NULLIFY", preview.tables().get(0).columns().get(1).algorithmCode(),
                "detected null algorithm");
        assertEquals("TRUNCATE_TABLE", preview.tables().get(1).algorithmCode(),
                "detected structural algorithm");
        assertEquals(64, preview.checksum().length(), "DDL checksum length");

        SqlInventoryPreview literalPreview = new OracleSqlInventoryPreviewService().preview(
                "ORACLE", "UPDATE APP.CUSTOMER SET FIRST_NAME = 'VALUE', CITY = N'GENEVA', NOTE = 'MASKED';");
        assertEquals("ORACLE_EXPRESSION_EXACT",
                literalPreview.tables().get(0).columns().get(0).algorithmCode(),
                "literal first-name algorithm suggestion");
        assertEquals("ORACLE_EXPRESSION_EXACT",
                literalPreview.tables().get(0).columns().get(1).algorithmCode(),
                "national literal city algorithm suggestion");
        assertEquals("ORACLE_EXPRESSION_EXACT",
                literalPreview.tables().get(0).columns().get(2).algorithmCode(),
                "generic literal algorithm suggestion");
        assertEquals("MATCHED",
                literalPreview.tables().get(0).columns().get(0).matchStatus(),
                "literal suggestions require review");

        SqlInventoryPreview criteriaPreview = new OracleSqlInventoryPreviewService().preview(
                "ORACLE", "UPDATE APP.CUSTOMER SET EMAIL = NULL WHERE ACTIVE = 'Y';");
        assertEquals("ACTIVE = 'Y'", criteriaPreview.tables().get(0).filterExpression(),
                "UPDATE criterion is retained");

        SqlInventoryPreview sqlPlusVariablePreview = new OracleSqlInventoryPreviewService().preview(
                "ORACLE", """
                        UPDATE &schema..CUSTOMER customer
                           SET customer.EMAIL = NULL,
                               customer.CITY = 'MASKED'
                         WHERE(customer.ACTIVE = 'Y'
                           AND EXISTS (SELECT 1 FROM AUDIT_LOG log
                                        WHERE log.CUSTOMER_ID = customer.ID));
                        """);
        assertEquals("", sqlPlusVariablePreview.tables().get(0).schema(),
                "SQL*Plus schema variable is ignored");
        assertEquals("CUSTOMER", sqlPlusVariablePreview.tables().get(0).name(),
                "table name after SQL*Plus schema variable");
        assertEquals(2, sqlPlusVariablePreview.columnCount(),
                "multiline UPDATE assignments with SQL*Plus schema variable");
        assertEquals("(customer.ACTIVE = 'Y' AND EXISTS (SELECT 1 FROM AUDIT_LOG log WHERE log.CUSTOMER_ID = customer.ID))",
                sqlPlusVariablePreview.tables().get(0).filterExpression().replaceAll("\\s+", " "),
                "top-level UPDATE criterion is retained exactly");

        SqlInventoryPreview persistentSqlPlusVariablePreview =
                new OracleSqlInventoryPreviewService().preview(
                        "ORACLE", "UPDATE &&owner..\"Customer\" SET EMAIL = NULL WHERE STATUS='A';");
        assertEquals("", persistentSqlPlusVariablePreview.tables().get(0).schema(),
                "persistent SQL*Plus schema variable is ignored");
        assertEquals("Customer", persistentSqlPlusVariablePreview.tables().get(0).name(),
                "quoted table name after persistent SQL*Plus variable");
        assertEquals("STATUS='A'", persistentSqlPlusVariablePreview.tables().get(0).filterExpression(),
                "criterion without spaces around the operator is retained");

        SqlInventoryPreview deletePreview = new OracleSqlInventoryPreviewService().preview(
                "ORACLE", """
                        UPDATE APP.CUSTOMER SET EMAIL = NULL WHERE ACTIVE = 'Y';
                        DELETE FROM APP.CUSTOMER WHERE RETENTION_FLAG = 'Y';
                        UPDATE APP.CUSTOMER SET CITY = 'VALUE';
                        """);
        assertEquals(1, deletePreview.tables().size(),
                "DELETE makes column algorithms obsolete regardless of statement order");
        assertEquals(0, deletePreview.columnCount(), "obsolete column algorithms are removed");
        assertEquals("DELETE_TABLE_ROWS", deletePreview.tables().get(0).algorithmCode(),
                "DELETE remains the applicable table algorithm");
        assertEquals("RETENTION_FLAG = 'Y'", deletePreview.tables().get(0).filterExpression(),
                "DELETE criterion is retained");

        SqlInventoryPreview truncatePreview = new OracleSqlInventoryPreviewService().preview(
                "ORACLE", """
                        TRUNCATE TABLE APP.CUSTOMER;
                        DELETE FROM APP.CUSTOMER WHERE RETENTION_FLAG = 'Y';
                        UPDATE APP.CUSTOMER SET EMAIL = NULL;
                        """);
        assertEquals(1, truncatePreview.tables().size(),
                "TRUNCATE makes all other table algorithms obsolete");
        assertEquals("TRUNCATE_TABLE", truncatePreview.tables().get(0).algorithmCode(),
                "TRUNCATE remains the applicable table algorithm");

        SqlInventoryPreview plsqlPreview = new OracleSqlInventoryPreviewService().preview(
                "ORACLE", """
                        BEGIN
                          BEGIN
                            UPDATE &schema..CUSTOMER
                               SET EMAIL = NULL
                             WHERE STATUS = 'INACTIVE';
                          END;
                          EXECUTE IMMEDIATE 'UPDATE APP.ACCOUNT SET LABEL = NULL';
                          EXECUTE IMMEDIATE runtime_sql;
                        END;
                        /
                        """);
        assertEquals(2, plsqlPreview.tables().size(),
                "static DML is extracted from PL/SQL blocks");
        assertEquals("CUSTOMER", plsqlPreview.tables().get(0).name(),
                "PL/SQL wrapped UPDATE table");
        assertEquals("ACCOUNT", plsqlPreview.tables().get(1).name(),
                "static EXECUTE IMMEDIATE table");
        assertEquals(true, plsqlPreview.warnings().stream().anyMatch(
                        warning -> warning.contains("runtime values")),
                "runtime dynamic SQL remains visible for review");

        SqlInventoryPreview reconstructedDynamic = new OracleSqlInventoryPreviewService().preview(
                "ORACLE", """
                        DECLARE
                          v_sql VARCHAR2(4000);
                        BEGIN
                          v_sql := 'UPDATE ' || v_owner ||
                                   '.CUSTOMER SET CITY = NULL WHERE STATUS = ''A''';
                          EXECUTE IMMEDIATE v_sql;
                        END;
                        /
                        """);
        assertEquals(1, reconstructedDynamic.tables().size(),
                "concatenated dynamic SQL is reconstructed");
        assertEquals("", reconstructedDynamic.tables().get(0).schema(),
                "runtime owner is bound to selected connection schema");
        assertEquals("STATUS = 'A'", reconstructedDynamic.tables().get(0).filterExpression(),
                "dynamic SQL criterion is reconstructed");

        SqlInventoryPreview mergePreview = new OracleSqlInventoryPreviewService().preview(
                "ORACLE", """
                        MERGE INTO APP.CUSTOMER target
                        USING APP.CUSTOMER_STAGE source
                           ON (source.ID = target.ID)
                        WHEN MATCHED THEN UPDATE
                           SET target.EMAIL = NULL,
                               target.CITY = 'MASKED'
                         WHERE target.ACTIVE = 'Y'
                        WHEN NOT MATCHED THEN INSERT (ID) VALUES (source.ID);
                        """);
        assertEquals(1, mergePreview.tables().size(), "MERGE UPDATE target is detected");
        assertEquals(2, mergePreview.columnCount(), "MERGE assignments are detected");
        assertEquals("REVIEW_REQUIRED",
                mergePreview.tables().get(0).columns().get(0).matchStatus(),
                "MERGE rules always require semantic review");
        assertEquals(true, mergePreview.warnings().stream().anyMatch(
                        warning -> warning.contains("USING/ON")),
                "MERGE join semantics are reported");
    }

    private static void verifiesMultiDialectTransformerFoundation() {
        MultiDialectSqlInventoryPreviewService transformer =
                new MultiDialectSqlInventoryPreviewService(
                        new OracleSqlInventoryPreviewService(),
                        new SqlServerSqlInventoryPreviewService(),
                        new PostgresqlSqlInventoryPreviewService());
        SqlInventoryPreview preview = transformer.preview(
                "oracle", "UPDATE APP.CUSTOMER SET EMAIL = NULL;");
        assertEquals("ORACLE", preview.technology(), "dialect registry normalizes technology");
        SqlInventoryPreview sqlServer = transformer.preview(
                "sqlserver", "UPDATE dbo.CUSTOMER SET EMAIL = NULL;");
        assertEquals("SQLSERVER", sqlServer.technology(),
                "SQL Server parser is registered");
        SqlInventoryPreview postgresql = transformer.preview(
                "postgresql", "UPDATE public.customer SET email = NULL;");
        assertEquals("POSTGRESQL", postgresql.technology(),
                "PostgreSQL parser is registered");
    }

    private static void verifiesSqlServerSqlInventoryPreview() {
        SqlServerSqlInventoryPreviewService parser =
                new SqlServerSqlInventoryPreviewService();

        SqlInventoryPreview direct = parser.parse("""
                UPDATE [dbo].[CUSTOMER]
                   SET [EMAIL] = NULL,
                       [TOKEN] = HASHBYTES('SHA2_256', CONVERT(varbinary(max), [TOKEN]))
                 WHERE [ACTIVE] = 1;
                GO
                TRUNCATE TABLE [dbo].[CUSTOMER_STAGE];
                """);
        assertEquals(2, direct.statementCount(), "T-SQL GO batch count");
        assertEquals("dbo", direct.tables().get(0).schema(),
                "bracketed SQL Server schema");
        assertEquals("CUSTOMER", direct.tables().get(0).name(),
                "bracketed SQL Server table");
        assertEquals("NULLIFY", direct.tables().get(0).columns().get(0).algorithmCode(),
                "T-SQL NULL algorithm");
        assertEquals("HASH_SHA256", direct.tables().get(0).columns().get(1).algorithmCode(),
                "T-SQL HASHBYTES algorithm");
        assertEquals("[ACTIVE] = 1", direct.tables().get(0).filterExpression(),
                "T-SQL WHERE criterion");

        SqlInventoryPreview joined = parser.parse("""
                UPDATE target
                   SET target.EMAIL = NULL
                  FROM dbo.CUSTOMER AS target
                  JOIN dbo.CUSTOMER_STAGE AS source ON source.ID = target.ID
                 WHERE source.READY = 1;
                """);
        assertEquals("DBO", joined.tables().get(0).schema(),
                "UPDATE FROM alias schema resolution");
        assertEquals("CUSTOMER", joined.tables().get(0).name(),
                "UPDATE FROM alias table resolution");
        assertEquals("REVIEW_REQUIRED", joined.tables().get(0).columns().get(0).matchStatus(),
                "UPDATE FROM requires review");
        assertEquals(true, joined.warnings().stream().anyMatch(
                        warning -> warning.contains("join semantics")),
                "UPDATE FROM semantic warning");

        SqlInventoryPreview cte = parser.parse("""
                WITH candidates AS (
                    SELECT customer.ID
                      FROM dbo.CUSTOMER AS customer
                     WHERE customer.ACTIVE = 0
                )
                UPDATE candidates SET EMAIL = NULL;
                """);
        assertEquals("CUSTOMER", cte.tables().get(0).name(),
                "CTE target is resolved to its physical source");
        assertEquals("REVIEW_REQUIRED", cte.tables().get(0).columns().get(0).matchStatus(),
                "CTE update requires review");

        SqlInventoryPreview merge = parser.parse("""
                MERGE INTO dbo.CUSTOMER AS target
                USING dbo.CUSTOMER_STAGE AS source ON source.ID = target.ID
                WHEN MATCHED THEN UPDATE SET target.EMAIL = NULL
                WHEN NOT MATCHED THEN INSERT (ID) VALUES (source.ID);
                """);
        assertEquals(1, merge.columnCount(), "T-SQL MERGE assignment");
        assertEquals("REVIEW_REQUIRED", merge.tables().get(0).columns().get(0).matchStatus(),
                "T-SQL MERGE requires review");

        SqlInventoryPreview dynamic = parser.parse("""
                DECLARE @sql nvarchar(max);
                SET @sql = N'UPDATE dbo.CUSTOMER SET EMAIL = NULL WHERE ACTIVE = 1';
                EXEC sys.sp_executesql @sql;
                """);
        assertEquals(1, dynamic.tables().size(), "sp_executesql is reconstructed");
        assertEquals("ACTIVE = 1", dynamic.tables().get(0).filterExpression(),
                "dynamic T-SQL criterion");
    }

    private static void verifiesPostgresqlSqlInventoryPreview() {
        PostgresqlSqlInventoryPreviewService parser =
                new PostgresqlSqlInventoryPreviewService();

        SqlInventoryPreview direct = parser.parse("""
                UPDATE public.customer
                   SET email = NULL,
                       token = encode(digest(token::text, 'sha256'), 'hex')
                 WHERE active IS TRUE
                 RETURNING id;
                TRUNCATE TABLE public.customer_stage RESTART IDENTITY;
                """);
        assertEquals(2, direct.statementCount(), "PostgreSQL statement count");
        assertEquals("public", direct.tables().get(0).schema(),
                "PostgreSQL unquoted schema is folded to lower case");
        assertEquals("customer", direct.tables().get(0).name(),
                "PostgreSQL unquoted table is folded to lower case");
        assertEquals("NULLIFY", direct.tables().get(0).columns().get(0).algorithmCode(),
                "PostgreSQL NULL algorithm");
        assertEquals("HASH_SHA256", direct.tables().get(0).columns().get(1).algorithmCode(),
                "PostgreSQL digest algorithm");
        assertEquals("active IS TRUE", direct.tables().get(0).filterExpression(),
                "PostgreSQL RETURNING is excluded from the criterion");

        SqlInventoryPreview quoted = parser.parse(
                "UPDATE \"Tenant\".\"Customer\" SET \"Email\" = NULL;");
        assertEquals("Tenant", quoted.tables().get(0).schema(),
                "quoted PostgreSQL schema keeps its case");
        assertEquals("Customer", quoted.tables().get(0).name(),
                "quoted PostgreSQL table keeps its case");

        SqlInventoryPreview dollarLiteral = parser.parse(
                "UPDATE public.customer SET note = $mask$masked;value$mask$;");
        assertEquals("MASK_STRING", dollarLiteral.tables().get(0).columns().get(0).algorithmCode(),
                "PostgreSQL dollar-quoted string stays one assignment");

        SqlInventoryPreview joined = parser.parse("""
                UPDATE public.customer AS target
                   SET email = NULL
                  FROM public.customer_stage AS source
                 WHERE source.id = target.id;
                """);
        assertEquals("REVIEW_REQUIRED", joined.tables().get(0).columns().get(0).matchStatus(),
                "PostgreSQL UPDATE FROM requires review");
        assertEquals(true, joined.warnings().stream().anyMatch(
                        warning -> warning.contains("join semantics")),
                "PostgreSQL UPDATE FROM semantic warning");

        SqlInventoryPreview merge = parser.parse("""
                MERGE INTO public.customer AS target
                USING public.customer_stage AS source ON source.id = target.id
                WHEN MATCHED THEN UPDATE SET email = NULL
                WHEN NOT MATCHED THEN DO NOTHING;
                """);
        assertEquals(1, merge.columnCount(), "PostgreSQL MERGE assignment");
        assertEquals("REVIEW_REQUIRED", merge.tables().get(0).columns().get(0).matchStatus(),
                "PostgreSQL MERGE requires review");

        SqlInventoryPreview dynamic = parser.parse("""
                DO $$
                DECLARE
                    v_sql text;
                BEGIN
                    v_sql := 'UPDATE public.customer SET email = NULL WHERE active IS TRUE';
                    EXECUTE v_sql;
                END
                $$;
                """);
        assertEquals(1, dynamic.tables().size(), "PL/pgSQL dynamic SQL is reconstructed");
        assertEquals("active IS TRUE", dynamic.tables().get(0).filterExpression(),
                "dynamic PostgreSQL criterion");

        SqlInventoryPreview delete = parser.parse("""
                DELETE FROM public.customer AS target
                 USING public.customer_stage AS source
                 WHERE source.id = target.id;
                """);
        assertEquals("DELETE_TABLE_ROWS", delete.tables().get(0).algorithmCode(),
                "PostgreSQL DELETE USING target");
        assertEquals(true, delete.warnings().stream().anyMatch(
                        warning -> warning.contains("DELETE USING")),
                "PostgreSQL DELETE USING warning");
    }

    private static void verifiesDraftInventoryCreation() {
        String sql = "UPDATE APP.CUSTOMER SET EMAIL = NULL;";
        CreateDraftInventoryService service = new CreateDraftInventoryService(
                new OracleSqlInventoryPreviewService(),
                (dataSourceId, code, name, sourceSql, preview, createdBy) ->
                        new DraftInventoryResult(71, "TRANSFORM-TEST", 81, code, 1,
                                "DRAFT_CREATED", preview.columnCount(), 0, 0,
                                preview.checksum()),
                "tester");
        DraftInventoryResult result = service.create(9, "customer_masking",
                "Customer masking", sql);
        assertEquals("CUSTOMER_MASKING", result.inventoryCode(), "normalized inventory code");
        assertEquals(1, result.columnRulesCreated(), "draft column rules");
    }

    private static void verifiesSuccessfulPlanning() {
        InMemoryJobs jobs = new InMemoryJobs(job(1));
        List<ApplicationAuditEvent> events = new ArrayList<>();
        PlanJobService service = new PlanJobService(jobs, id -> { },
                request -> plan(),
                (jobId, plan) -> { }, events::add,
                () -> Instant.parse("2026-08-09T12:00:00Z"));

        PlanJobResult result = service.plan(new PlanJobCommand(new JobId(1)));
        assertEquals(JobStatus.PLANNED, result.status(), "result status");
        assertEquals(JobStatus.PLANNED, jobs.current.status(), "saved status");
        assertEquals("JOB_PLANNED", events.get(0).eventType(), "audit event");
    }

    private static void verifiesFailedPlanning() {
        InMemoryJobs jobs = new InMemoryJobs(job(2));
        List<ApplicationAuditEvent> events = new ArrayList<>();
        PlanJobService service = new PlanJobService(jobs,
                id -> { throw new IllegalStateException("Ruleset is not published"); },
                request -> plan(),
                (jobId, plan) -> { }, events::add, Instant::now);
        try {
            service.plan(new PlanJobCommand(new JobId(2)));
            throw new AssertionError("Planning failure was expected");
        } catch (IllegalStateException expected) {
            assertEquals(JobStatus.PLAN_FAILED, jobs.current.status(), "failed status");
            assertEquals("JOB_PLAN_FAILED", events.get(0).eventType(), "failure audit");
        }
    }

    private static void verifiesSuccessfulPreflight() {
        AnonymizationJob planned = job(3).transitionTo(JobStatus.PLANNING).withPlan(CHECKSUM);
        InMemoryJobs jobs = new InMemoryJobs(planned);
        List<ApplicationAuditEvent> events = new ArrayList<>();
        PreflightJobService service = new PreflightJobService(jobs,
                id -> List.of("checksum verified"),
                (source, id) -> List.of("target connected", "column verified"),
                events::add, () -> Instant.parse("2026-08-09T12:00:00Z"));
        PreflightJobResult result = service.preflight(new PreflightJobCommand(new JobId(3)));
        assertEquals(JobStatus.PREFLIGHT_PASSED, result.status(), "preflight status");
        assertEquals("JOB_PREFLIGHT_PASSED", events.get(0).eventType(), "preflight audit");
    }

    private static void verifiesSuccessfulDirectExecution() {
        AnonymizationJob ready = job(4).transitionTo(JobStatus.PLANNING).withPlan(CHECKSUM)
                .transitionTo(JobStatus.PREFLIGHTING)
                .transitionTo(JobStatus.PREFLIGHT_PASSED);
        InMemoryJobs jobs = new InMemoryJobs(ready);
        ExecutablePlan plan = new ExecutablePlan(ready.id(), CHECKSUM,
                List.of(new ExecutableStep(401, 20,
                        "UPDATE FORMATION.EMP SET ENAME = 'X'", CHECKSUM)));
        InMemoryJournal journal = new InMemoryJournal(jobs);
        ExecuteJobService service = new ExecuteJobService(jobs, id -> plan,
                ignored -> new ExecutionOutcome(List.of(new StepExecutionResult(401, 14))),
                journal, () -> Instant.parse("2026-08-09T12:00:00Z"));
        ExecuteJobResult result = service.execute(
                new ExecuteJobCommand(ready.id(), ExecutionMode.DIRECT, RecoveryMode.NEW));
        assertEquals(JobStatus.VALIDATING, result.status(), "execution status");
        assertEquals(14L, result.rowsProcessed(), "processed rows");
        assertEquals("JOB_EXECUTION_SUCCEEDED", journal.lastEvent.eventType(),
                "execution audit");
    }

    private static void verifiesCancelledExecution() {
        AnonymizationJob ready = job(40).transitionTo(JobStatus.PLANNING).withPlan(CHECKSUM)
                .transitionTo(JobStatus.PREFLIGHTING)
                .transitionTo(JobStatus.PREFLIGHT_PASSED);
        InMemoryJobs jobs = new InMemoryJobs(ready);
        ExecutablePlan plan = new ExecutablePlan(ready.id(), CHECKSUM,
                List.of(new ExecutableStep(4001, 20,
                        "UPDATE FORMATION.EMP SET ENAME = 'X'", CHECKSUM)));
        InMemoryJournal journal = new InMemoryJournal(jobs);
        ExecuteJobService service = new ExecuteJobService(jobs, id -> plan,
                ignored -> { throw new JobExecutionCancelledException(40); },
                journal, () -> Instant.parse("2026-08-09T12:00:00Z"));
        try {
            service.execute(new ExecuteJobCommand(ready.id(),
                    ExecutionMode.CHUNKED, RecoveryMode.NEW));
            throw new AssertionError("Execution cancellation was expected");
        } catch (JobExecutionCancelledException expected) {
            assertEquals(JobStatus.CANCELLED, jobs.current.status(), "cancelled status");
            assertEquals("JOB_EXECUTION_CANCELLED", journal.lastEvent.eventType(),
                    "cancellation audit");
        }
    }

    private static void verifiesValidationAndReport() {
        AnonymizationJob validating = job(5).transitionTo(JobStatus.PLANNING)
                .withPlan(CHECKSUM).transitionTo(JobStatus.PREFLIGHTING)
                .transitionTo(JobStatus.PREFLIGHT_PASSED)
                .transitionTo(JobStatus.RUNNING).transitionTo(JobStatus.VALIDATING);
        InMemoryJobs jobs = new InMemoryJobs(validating);
        ValidationOutcome outcome = new ValidationOutcome(
                List.of("row count verified"),
                List.of(new ValidationMetric("ROWS_PROCESSED", 14)));
        InMemoryValidationJournal validationJournal = new InMemoryValidationJournal();
        new ValidateJobService(jobs, (source, id) -> outcome,
                validationJournal, Instant::now)
                .validate(new ValidateJobCommand(validating.id()));
        assertEquals("JOB_VALIDATION_PASSED", validationJournal.event.eventType(),
                "validation audit");

        InMemoryReportRepository reports = new InMemoryReportRepository();
        JobReport report = new GenerateReportService(jobs, reports, Instant::now)
                .generate(new GenerateReportCommand(validating.id()));
        assertEquals(14L, report.rowsProcessed(), "report rows");
        assertEquals(JobStatus.SUCCEEDED, reports.completed.status(), "report status");
    }

    private static AnonymizationJob job(long id) {
        return AnonymizationJob.draft(new JobId(id),
                new JobReference("FORMATION-EMP-00" + id),
                new RulesetVersionId(100 + id), new DataSourceId(200 + id));
    }

    private static GeneratedPlan plan() {
        return new GeneratedPlan(CHECKSUM, List.of(new GeneratedPlanStep(
                301, "FORMATION.EMP", "UPDATE FORMATION.EMP SET ENAME = 'X'", CHECKSUM)));
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class InMemoryJobs implements JobRepository {
        private AnonymizationJob current;

        private InMemoryJobs(AnonymizationJob current) {
            this.current = current;
        }

        @Override
        public AnonymizationJob get(JobId id) {
            return current;
        }

        @Override
        public void save(AnonymizationJob job) {
            current = job;
        }
    }

    private static final class InMemoryJournal implements ExecutionJournal {
        private final InMemoryJobs jobs;
        private ApplicationAuditEvent lastEvent;

        private InMemoryJournal(InMemoryJobs jobs) {
            this.jobs = jobs;
        }

        @Override
        public void start(AnonymizationJob job, ExecutablePlan plan,
                ApplicationAuditEvent event) {
            jobs.save(job);
            lastEvent = event;
        }

        @Override
        public void succeed(AnonymizationJob job, ExecutionOutcome outcome,
                ApplicationAuditEvent event) {
            jobs.save(job);
            lastEvent = event;
        }

        @Override
        public void fail(AnonymizationJob job, ExecutablePlan plan,
                RuntimeException error, ApplicationAuditEvent event) {
            jobs.save(job);
            lastEvent = event;
        }

        @Override
        public void cancel(AnonymizationJob job, ExecutablePlan plan,
                ApplicationAuditEvent event) {
            jobs.save(job);
            lastEvent = event;
        }
    }

    private static final class InMemoryValidationJournal implements ValidationJournal {
        private ApplicationAuditEvent event;

        @Override
        public void succeed(AnonymizationJob job, ValidationOutcome outcome,
                ApplicationAuditEvent event) {
            this.event = event;
        }

        @Override
        public void fail(AnonymizationJob job, RuntimeException error,
                ApplicationAuditEvent event) {
            this.event = event;
        }
    }

    private static final class InMemoryReportRepository implements JobReportRepository {
        private AnonymizationJob completed;

        @Override
        public JobReport load(JobId jobId) {
            return new JobReport(jobId, new JobReference("FORMATION-EMP-005"),
                    14, 3, List.of(new ValidationMetric("ROWS_PROCESSED", 14)));
        }

        @Override
        public void complete(AnonymizationJob job, JobReport report,
                ApplicationAuditEvent event) {
            completed = job;
        }
    }
}
