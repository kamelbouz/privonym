package com.privonym.cli;

import com.privonym.application.service.GeneratedPlan;
import com.privonym.application.service.GeneratedPlanStep;
import com.privonym.application.service.JobPlanningRequest;
import com.privonym.application.service.PlanJobCommand;
import com.privonym.application.service.PlanJobService;
import com.privonym.application.service.PreflightJobCommand;
import com.privonym.application.service.PreflightJobResult;
import com.privonym.application.service.PreflightJobService;
import com.privonym.application.service.ExecuteJobCommand;
import com.privonym.application.service.ExecuteJobResult;
import com.privonym.application.service.ExecuteJobService;
import com.privonym.application.service.ValidateJobCommand;
import com.privonym.application.service.ValidateJobResult;
import com.privonym.application.service.ValidateJobService;
import com.privonym.application.service.GenerateReportCommand;
import com.privonym.application.service.GenerateReportService;
import com.privonym.application.service.JobReport;
import com.privonym.application.service.ValidationMetric;
import com.privonym.domain.execution.ExecutionMode;
import com.privonym.domain.execution.RecoveryMode;
import com.privonym.domain.job.AnonymizationJob;
import com.privonym.domain.job.JobId;
import com.privonym.repository.oracle.OraclePlanningAdapter;
import com.privonym.repository.oracle.OracleConnectionDetails;
import com.privonym.repository.oracle.OracleTargetPreflightAdapter;
import com.privonym.repository.oracle.OracleMeasuredPlanExecutor;
import com.privonym.repository.oracle.OracleTargetDataValidator;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

public final class PrivonymCli {
    private PrivonymCli() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (Exception exception) {
            System.err.println("ERROR: " + exception.getMessage());
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            return 2;
        }

        String command = args[0].toLowerCase(java.util.Locale.ROOT);
        String jobReference = args[1];
        boolean save = Arrays.stream(args).anyMatch("--save"::equalsIgnoreCase);
        boolean confirmed = Arrays.stream(args).anyMatch("--confirm"::equalsIgnoreCase);
        ExecutionMode executionMode = parseExecutionMode(args);
        RecoveryMode recoveryMode = Arrays.stream(args).anyMatch("--resume"::equalsIgnoreCase)
                ? RecoveryMode.RESUME : RecoveryMode.NEW;
        int chunkSize = parseChunkSize(args);
        if (!List.of("preview", "preflight", "execute", "validate", "report")
                .contains(command)) {
            printUsage();
            return 2;
        }
        if (!"preview".equals(command) && save) {
            throw new IllegalArgumentException("--save is valid only with preview");
        }
        if ("execute".equals(command) && !confirmed) {
            throw new IllegalArgumentException(
                    "Execution changes target data; repeat with --confirm");
        }
        if (!"execute".equals(command) && confirmed) {
            throw new IllegalArgumentException("--confirm is valid only with execute");
        }
        if (!"execute".equals(command) && executionMode != ExecutionMode.DIRECT) {
            throw new IllegalArgumentException("--mode is valid only with execute");
        }
        Properties configuration = loadConfiguration();
        String url = required(configuration, "repository.url");
        String user = required(configuration, "repository.user");
        char[] password = repositoryPassword(args, command, user);

        try {
            DriverManager.setLoginTimeout(15);
            try (Connection connection = com.privonym.repository.RepositoryConnections.open(url, user, new String(password))) {
                connection.setReadOnly("preview".equals(command) && !save);
                connection.setAutoCommit(false);
                if ("preview".equals(command)) {
                    runPreview(connection, jobReference, save);
                } else if ("preflight".equals(command)) {
                    runPreflight(connection, jobReference);
                } else if ("execute".equals(command)) {
                    runExecute(connection, jobReference, executionMode, recoveryMode, chunkSize,
                            url, user, password);
                } else if ("validate".equals(command)) {
                    runValidate(connection, jobReference);
                } else {
                    runReport(connection, jobReference);
                }
            }
        } finally {
            Arrays.fill(password, '\0');
        }
        return 0;
    }

    private static void runPreview(
            Connection connection, String jobReference, boolean save) throws Exception {
        OraclePlanningAdapter adapter = new OraclePlanningAdapter(connection);
        AnonymizationJob job = resolveJob(adapter, jobReference);
        GeneratedPlan plan;
        if (save) {
            PlanJobService service = new PlanJobService(
                    adapter, adapter, adapter, adapter, adapter, Instant::now);
            try {
                service.plan(new PlanJobCommand(job.id()));
                connection.commit();
            } catch (RuntimeException error) {
                connection.commit();
                throw error;
            }
            plan = adapter.generatePlan(new JobPlanningRequest(
                    job.id(), job.reference(), job.rulesetVersionId(), job.dataSourceId()));
        } else {
            plan = adapter.generatePlan(new JobPlanningRequest(
                    job.id(), job.reference(), job.rulesetVersionId(), job.dataSourceId()));
        }

        for (GeneratedPlanStep step : plan.steps()) {
            System.out.println("-- Job: " + job.reference().value());
            System.out.println("-- Table: " + step.qualifiedTable());
            System.out.println("-- SHA-256: " + step.checksum().value());
            System.out.println(step.generatedCode());
            System.out.println();
        }

        if (save) {
            System.out.println("Plan saved through Privonym V2 application service.");
            System.out.println("Plan SHA-256: " + plan.checksum().value());
        } else {
            connection.rollback();
            System.out.println("Read-only preview completed; nothing was saved.");
        }
    }

    private static void runPreflight(Connection connection, String jobReference)
            throws Exception {
        OraclePlanningAdapter repository = new OraclePlanningAdapter(connection);
        AnonymizationJob job = resolveJob(repository, jobReference);
        OracleConnectionDetails details = repository.loadConnectionDetails(job.dataSourceId());
        char[] targetPassword = repository.loadSavedTargetPassword(job.dataSourceId());
        try {
            try (Connection target = DriverManager.getConnection(
                    details.jdbcUrl(), details.username(), new String(targetPassword))) {
                target.setReadOnly(true);
                target.setAutoCommit(false);
                com.privonym.application.port.out.TargetPreflightAdapter targetPreflight =
                        switch (details.technologyCode()) {
                            case "POSTGRESQL" -> new com.privonym.repository.postgresql.PostgresqlTargetPreflightAdapter(connection, target);
                            case "SQLSERVER" -> new com.privonym.repository.sqlserver.SqlServerTargetPreflightAdapter(connection, target);
                            default -> new OracleTargetPreflightAdapter(connection, target);
                        };
                PreflightJobService service = new PreflightJobService(
                        repository, repository, targetPreflight,
                        repository, Instant::now);
                try {
                    PreflightJobResult result = service.preflight(
                            new PreflightJobCommand(job.id()));
                    connection.commit();
                    target.rollback();
                    System.out.println("Privonym V2 preflight passed.");
                    System.out.println("Job: " + job.reference().value()
                            + " (" + result.status() + ")");
                    for (String message : result.messages()) {
                        System.out.println("[OK] " + message);
                    }
                    System.out.println("No target SQL was executed.");
                } catch (RuntimeException error) {
                    connection.commit();
                    target.rollback();
                    throw error;
                }
            }
        } finally {
            Arrays.fill(targetPassword, '\0');
        }
    }

    private static void runExecute(Connection connection, String jobReference,
            ExecutionMode executionMode, RecoveryMode recoveryMode, int chunkSize,
            String repositoryUrl, String repositoryUser, char[] repositoryPassword)
            throws Exception {
        OraclePlanningAdapter repository = new OraclePlanningAdapter(connection);
        AnonymizationJob job = resolveJob(repository, jobReference);
        OracleConnectionDetails details = repository.loadConnectionDetails(job.dataSourceId());
        char[] targetPassword = readPassword(
                "Target password for " + details.username() + ": ");
        try {
            try (Connection target = DriverManager.getConnection(
                    details.jdbcUrl(), details.username(), new String(targetPassword))) {
                target.setReadOnly(false);
                target.setAutoCommit(false);
                if ("SQLSERVER".equals(details.technologyCode())) {
                    try (java.sql.Statement statement = target.createStatement()) {
                        statement.execute("SET QUOTED_IDENTIFIER ON");
                        statement.execute("SET XACT_ABORT ON");
                    }
                }
                ExecuteJobResult result;
                if (executionMode == ExecutionMode.CHUNKED) {
                    try (com.privonym.repository.oracle.OracleParallelPlanExecutor executor =
                            new com.privonym.repository.oracle.OracleParallelPlanExecutor(
                                    repositoryUrl, repositoryUser, repositoryPassword,
                                    details.jdbcUrl(), details.username(), targetPassword,
                                    4, "CHUNKED", chunkSize, chunkSize)) {
                        result = new ExecuteJobService(repository, repository, executor,
                                repository, Instant::now).execute(
                                        new ExecuteJobCommand(job.id(), executionMode,
                                                recoveryMode));
                    }
                } else {
                    result = new ExecuteJobService(repository, repository,
                            new OracleMeasuredPlanExecutor(connection, target,()->com.privonym.repository.RepositoryConnections.open(repositoryUrl,repositoryUser,new String(repositoryPassword))), repository,
                            Instant::now).execute(new ExecuteJobCommand(job.id(), executionMode,
                                    recoveryMode));
                }
                System.out.println("Privonym V2 " + executionMode + " execution completed.");
                System.out.println("Job: " + job.reference().value()
                        + " (" + result.status() + ")");
                System.out.println("Executed steps: " + result.executedStepCount());
                System.out.println("Rows processed: " + result.rowsProcessed());
                System.out.println("Target transaction committed.");
            }
        } finally {
            Arrays.fill(targetPassword, '\0');
        }
    }

    private static void runValidate(Connection connection, String jobReference)
            throws Exception {
        OraclePlanningAdapter repository = new OraclePlanningAdapter(connection);
        AnonymizationJob job = resolveJob(repository, jobReference);
        OracleConnectionDetails details = repository.loadConnectionDetails(job.dataSourceId());
        char[] targetPassword = repository.loadSavedTargetPassword(job.dataSourceId());
        try {
            try (Connection target = DriverManager.getConnection(
                    details.jdbcUrl(), details.username(), new String(targetPassword))) {
                target.setReadOnly(true);
                target.setAutoCommit(false);
                ValidateJobService service = new ValidateJobService(repository,
                        new OracleTargetDataValidator(connection, target),
                        repository, Instant::now);
                ValidateJobResult result = service.validate(new ValidateJobCommand(job.id()));
                target.rollback();
                System.out.println("Privonym V2 validation passed.");
                for (String message : result.messages()) {
                    System.out.println("[OK] " + message);
                }
                for (ValidationMetric metric : result.metrics()) {
                    System.out.println("[METRIC] " + metric.name() + "=" + metric.value());
                }
                System.out.println("No target data was modified by validation.");
            }
        } finally {
            Arrays.fill(targetPassword, '\0');
        }
    }

    private static void runReport(Connection connection, String jobReference) {
        OraclePlanningAdapter repository = new OraclePlanningAdapter(connection);
        AnonymizationJob job = resolveJob(repository, jobReference);
        JobReport report = new GenerateReportService(repository, repository, Instant::now)
                .generate(new GenerateReportCommand(job.id()));
        System.out.println("Privonym V2 final report");
        System.out.println("Job: " + report.jobReference().value() + " (SUCCEEDED)");
        System.out.println("Succeeded steps before report: " + report.succeededSteps());
        System.out.println("Rows processed: " + report.rowsProcessed());
        for (ValidationMetric metric : report.metrics()) {
            System.out.println("[METRIC] " + metric.name() + "=" + metric.value());
        }
    }

    private static Properties loadConfiguration() throws IOException {
        String configuredPath = System.getenv("PRIVONYM_CONFIG");
        Path path = configuredPath == null || configuredPath.isBlank()
                ? Path.of("config", "privonym.properties")
                : Path.of(configuredPath);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static AnonymizationJob resolveJob(OraclePlanningAdapter repository,
            String identifier) {
        if (identifier != null && identifier.matches("[1-9][0-9]*")) {
            try {
                return repository.get(new JobId(Long.parseLong(identifier)));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Job identifier is too large: " + identifier);
            }
        }
        return repository.getByReference(identifier);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing configuration property: " + key);
        }
        return value.trim();
    }

    private static ExecutionMode parseExecutionMode(String[] args) {
        for (String argument : args) {
            if (argument.toLowerCase(java.util.Locale.ROOT).startsWith("--mode=")) {
                String value = argument.substring("--mode=".length())
                        .toUpperCase(java.util.Locale.ROOT);
                try {
                    return ExecutionMode.valueOf(value);
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("Unknown execution mode: " + value);
                }
            }
        }
        return ExecutionMode.DIRECT;
    }

    private static int parseChunkSize(String[] args) {
        for (String argument : args) {
            if (argument.toLowerCase(java.util.Locale.ROOT).startsWith("--chunk-size=")) {
                int size = Integer.parseInt(argument.substring("--chunk-size=".length()));
                if (size < 1 || size > 1_000_000) {
                    throw new IllegalArgumentException("Chunk size must be between 1 and 1000000");
                }
                return size;
            }
        }
        return 10_000;
    }

    private static char[] readPassword(String prompt) {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                    "No interactive console is available for the password prompt");
        }
        char[] password = console.readPassword(prompt);
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("A password is required");
        }
        return password;
    }

    private static char[] repositoryPassword(String[] arguments, String command, String user) {
        for (String argument : arguments) {
            if (argument.startsWith("--repository-password=")) {
                String value = argument.substring("--repository-password=".length());
                if (value.isEmpty()) throw new IllegalArgumentException("Repository password is empty");
                return value.toCharArray();
            }
        }
        if (("validate".equals(command) || "report".equals(command))
                && arguments.length >= 3 && !arguments[2].startsWith("--")) {
            return arguments[2].toCharArray();
        }
        String environmentPassword = System.getenv("PRIVONYM_REPOSITORY_PASSWORD");
        if (environmentPassword != null && !environmentPassword.isEmpty()) {
            return environmentPassword.toCharArray();
        }
        return readPassword("Repository password for " + user + ": ");
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  privonym.cmd preview <job-reference> [--save]");
        System.err.println("  privonym.cmd preflight <job-reference>");
        System.err.println("  privonym.cmd execute <job-reference> --confirm"
                + " [--mode=direct|lookup|chunked] [--chunk-size=N] [--resume]");
        System.err.println("  privonym.cmd validate <job-id-or-reference> [repository-password]");
        System.err.println("  privonym.cmd report <job-id-or-reference> [repository-password]");
    }
}
