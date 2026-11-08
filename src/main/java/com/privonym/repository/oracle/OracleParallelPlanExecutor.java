package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.SqlChecksum;
import com.privonym.application.port.out.TargetPlanExecutor;
import com.privonym.application.service.ExecutablePlan;
import com.privonym.application.service.ExecutableStep;
import com.privonym.application.service.ExecutionOutcome;
import com.privonym.application.service.JobExecutionCancelledException;
import com.privonym.application.service.StepExecutionResult;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes independent table steps and large-table chunks concurrently. */
public final class OracleParallelPlanExecutor implements TargetPlanExecutor, AutoCloseable {
    private static final ConcurrentMap<Long, OracleParallelPlanExecutor> ACTIVE_EXECUTIONS =
            new ConcurrentHashMap<>();
    private static final String IDENTIFIER = "(?:\\\"[^\\\"]+\\\"|\\[[^\\]]+\\]|[A-Za-z0-9_$#]+)";
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)^\\s*UPDATE\\s+(" + IDENTIFIER
                    + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?)\\s+SET\\s+");
    private static final Pattern DELETE = Pattern.compile(
            "(?is)^\\s*DELETE\\s+FROM\\s+(" + IDENTIFIER
                    + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?)");
    private static final Pattern TRUNCATE = Pattern.compile(
            "(?is)^\\s*TRUNCATE\\s+TABLE\\s+(" + IDENTIFIER
                    + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?)\\s*$");

    private final String repositoryUrl;
    private final String repositoryUser;
    private final char[] repositoryPassword;
    private final String targetUrl;
    private final String targetUser;
    private final char[] targetPassword;
    private final String targetTechnology;
    private final int parallelDegree;
    private final String executionMode;
    private final int chunkSize;
    private final long chunkThreshold;
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final Set<Statement> activeStatements = ConcurrentHashMap.newKeySet();
    private volatile long activeJobId;

    public OracleParallelPlanExecutor(String repositoryUrl, String repositoryUser,
            char[] repositoryPassword, String targetUrl, String targetUser, char[] targetPassword,
            int parallelDegree, String executionMode, int chunkSize, long chunkThreshold) {
        this.repositoryUrl = Objects.requireNonNull(repositoryUrl, "repositoryUrl");
        this.repositoryUser = Objects.requireNonNull(repositoryUser, "repositoryUser");
        this.repositoryPassword = Objects.requireNonNull(repositoryPassword, "repositoryPassword").clone();
        this.targetUrl = Objects.requireNonNull(targetUrl, "targetUrl");
        this.targetTechnology = technologyFromJdbcUrl(targetUrl);
        this.targetUser = Objects.requireNonNull(targetUser, "targetUser");
        this.targetPassword = Objects.requireNonNull(targetPassword, "targetPassword").clone();
        if (parallelDegree < 1 || parallelDegree > 32) {
            throw new IllegalArgumentException("Worker threads must be between 1 and 32");
        }
        this.parallelDegree = parallelDegree;
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode")
                .toUpperCase(Locale.ROOT);
        if (!List.of("AUTO", "DIRECT", "CHUNKED").contains(this.executionMode)) {
            throw new IllegalArgumentException("Invalid execution mode: " + executionMode);
        }
        if (chunkSize < 1_000 || chunkSize > 1_000_000
                || chunkThreshold < chunkSize) {
            throw new IllegalArgumentException("Invalid chunk configuration");
        }
        this.chunkSize = chunkSize;
        this.chunkThreshold = chunkThreshold;
    }

    @Override
    public ExecutionOutcome execute(ExecutablePlan plan) {
        long jobId = plan.jobId().value();
        activeJobId = jobId;
        if (ACTIVE_EXECUTIONS.putIfAbsent(jobId, this) != null) {
            throw new IllegalStateException("Job " + jobId + " already has an active executor");
        }
        AtomicInteger tableNumber = new AtomicInteger();
        ThreadFactory tableFactory = task -> {
            Thread thread = new Thread(task,
                    "privonym-job-" + jobId + "-table-" + tableNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        try {
            List<PreparedStep> prepared = prepare(plan);
            Map<Long,Integer> dependencyGroups = dependencyGroups(jobId);
            Map<Integer,Map<String,List<PreparedStep>>> levels = new TreeMap<>();
            for (PreparedStep step : prepared) {
                int level = dependencyGroups.getOrDefault(step.step().jobStepId(), 0);
                levels.computeIfAbsent(level, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(step.operation().table(), ignored -> new ArrayList<>())
                        .add(step);
            }
            List<StepExecutionResult> results = new ArrayList<>();
            int groupNumber = 0;
            for (Map<String,List<PreparedStep>> level : levels.values()) {
                List<List<PreparedStep>> tableGroups = new ArrayList<>(level.values());
                int concurrentTables = Math.max(1, Math.min(parallelDegree, tableGroups.size()));
                int baseWorkersPerTable = Math.max(1, parallelDegree / concurrentTables);
                int extraWorkers = parallelDegree % concurrentTables;
                ExecutorService tableWorkers = Executors.newFixedThreadPool(concurrentTables,
                        tableFactory);
                try {
                    List<Future<List<StepExecutionResult>>> tableFutures = new ArrayList<>();
                    for (int index = 0; index < tableGroups.size(); index++) {
                        List<PreparedStep> tableSteps = tableGroups.get(index);
                        int slot = index % concurrentTables;
                        int workersForTable = baseWorkersPerTable
                                + (slot < extraWorkers ? 1 : 0);
                        int currentGroup = ++groupNumber;
                        tableFutures.add(tableWorkers.submit(() -> executeTableGroup(jobId,
                                currentGroup, tableSteps, workersForTable)));
                    }
                    ExecutionException tableFailure = null;
                    for (Future<List<StepExecutionResult>> future : tableFutures) {
                        try { results.addAll(future.get()); }
                        catch (ExecutionException error) {
                            if (tableFailure == null) tableFailure = error;
                        }
                    }
                    if (tableFailure != null) throw tableFailure;
                } finally {
                    shutdownAndAwait(tableWorkers);
                }
            }
            return new ExecutionOutcome(results);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (cancellationRequested.get()) {
                throw new JobExecutionCancelledException(jobId, error);
            }
            throw new IllegalStateException("Parallel Job execution was interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = executionCause(error);
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Parallel Job execution failed", cause);
        } finally {
            activeStatements.clear();
            ACTIVE_EXECUTIONS.remove(jobId, this);
            activeJobId = 0;
        }
    }

    private Map<Long,Integer> dependencyGroups(long jobId) {
        Map<Long,Integer> groups = new LinkedHashMap<>();
        try (Connection repository = openRepository(); PreparedStatement statement =
                repository.prepareStatement("SELECT job_step_id,dependency_group FROM job_step WHERE job_id=? AND step_type='ANONYMIZE_TABLE'")) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) groups.put(rows.getLong(1), rows.getInt(2));
            }
            return groups;
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to load table dependency schedule: "
                    + error.getMessage(), error);
        }
    }

    private List<StepExecutionResult> executeTableGroup(long jobId, int groupNumber,
            List<PreparedStep> tableSteps, int workerCount)
            throws InterruptedException, ExecutionException {
        AtomicInteger workerNumber = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "privonym-job-" + jobId + "-table-"
                    + groupNumber + "-worker-" + workerNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService workers = Executors.newFixedThreadPool(workerCount, factory);
        try {
            List<StepExecutionResult> results = new ArrayList<>();
            for (PreparedStep step : tableSteps) {
                checkCancellation();
                long rows = step.chunked()
                        ? executeChunkedStep(step, workers, workerCount)
                        : executeDirectStep(step, workers);
                results.add(new StepExecutionResult(step.step().jobStepId(), rows));
            }
            return results;
        } finally {
            shutdownAndAwait(workers);
        }
    }

    private List<PreparedStep> prepare(ExecutablePlan plan) {
        List<PreparedStep> result = new ArrayList<>();
        try (Connection repository = openRepository(); Connection target = openTarget()) {
            target.setReadOnly(true);
            for (ExecutableStep step : plan.steps()) {
                checkCancellation();
                validateChecksum(step);
                Operation operation = operation(step.generatedCode());
                boolean eligible = chunkable(step.generatedCode(), operation);
                PrimaryKey primaryKey = eligible && !"ORACLE".equals(targetTechnology)
                        ? primaryKey(target, operation.table()) : null;
                if (primaryKey != null
                        && updatesPrimaryKey(step.generatedCode(), operation, primaryKey)) {
                    primaryKey = null;
                }
                if (!"ORACLE".equals(targetTechnology) && primaryKey == null) {
                    eligible = false;
                }
                long boundedCount = eligible && "AUTO".equals(executionMode)
                        ? boundedRowCount(target, operation.table(), chunkThreshold) : -1;
                boolean chunked = eligible && ("CHUNKED".equals(executionMode)
                        || "AUTO".equals(executionMode)
                                && boundedCount >= chunkThreshold);
                long expected = chunked
                        ? expectedRows(target, step.generatedCode(), operation)
                        : eligible && "AUTO".equals(executionMode)
                                ? boundedCount
                        : expectedRows(target, step.generatedCode(), operation);
                Map<Integer, SuccessfulChunk> completed = successfulChunks(repository,
                        step.jobStepId());
                TargetRowCountEvidence.capture(repository,target,step.jobStepId(),operation.table(),operation.type(),
                        expected,findTopLevelWhere(step.generatedCode(),operation.targetEnd())>=0,!completed.isEmpty());
                saveMetric(repository, step.jobStepId(), "ROWS_EXPECTED", expected, "ROWS");
                long chunks = chunked ? Math.max(1L, (expected + chunkSize - 1L) / chunkSize) : 1L;
                saveMetric(repository, step.jobStepId(), "CHUNKS_TOTAL", chunks, "CHUNKS");
                repository.commit();
                result.add(new PreparedStep(step, operation, expected, chunked, completed,
                        primaryKey));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            if (cancellationRequested.get()) {
                throw new JobExecutionCancelledException(plan.jobId().value(), error);
            }
            throw new IllegalStateException("Unable to prepare parallel execution: "
                    + error.getMessage(), error);
        }
    }

    private long executeDirectStep(PreparedStep prepared, ExecutorService workers)
            throws InterruptedException, ExecutionException {
        ChunkRange range = new ChunkRange(1, null, "DIRECT");
        SuccessfulChunk previous = prepared.completed().get(1);
        if (previous != null && previous.end().equals(range.end())) return previous.rows();
        return workers.submit(new Work(prepared.step(), prepared.operation(), range,
                prepared.expected(), false, prepared.primaryKey())::execute).get().rows();
    }

    private long executeChunkedStep(PreparedStep prepared, ExecutorService workers,
            int workerCount)
            throws InterruptedException, ExecutionException {
        long rowsProcessed = 0;
        int chunkNumber = 1;
        String previousEnd = null;
        try (Connection target = openTarget()) {
            target.setReadOnly(true);
            boolean finished = false;
            while (!finished) {
                checkCancellation();
                List<ChunkRange> batch = new ArrayList<>();
                for (int slot = 0; slot < workerCount; slot++) {
                    String end = findLastBoundary(target, prepared.operation().table(),
                            prepared.primaryKey(), previousEnd);
                    if (end == null) {
                        finished = true;
                        break;
                    }
                    batch.add(new ChunkRange(chunkNumber++, previousEnd, end));
                    previousEnd = end;
                }
                List<Future<WorkResult>> futures = new ArrayList<>();
                for (ChunkRange range : batch) {
                    SuccessfulChunk completed = prepared.completed().get(range.number());
                    if (completed != null && completed.end().equals(range.end())) {
                        rowsProcessed += completed.rows();
                    } else {
                        futures.add(workers.submit(new Work(prepared.step(), prepared.operation(),
                                range, prepared.expected(), true, prepared.primaryKey())::execute));
                    }
                }
                ExecutionException batchFailure = null;
                for (Future<WorkResult> future : futures) {
                    checkCancellation();
                    try {
                        rowsProcessed += future.get().rows();
                    } catch (ExecutionException error) {
                        // A failed worker must not make the Job terminal while the other
                        // workers from this batch are still changing target data. Keep
                        // draining the batch, then report the first failure.
                        if (batchFailure == null) batchFailure = error;
                    }
                }
                if (batchFailure != null) throw batchFailure;
            }
        } catch (SQLException error) {
            if (cancellationRequested.get()) {
                throw new JobExecutionCancelledException(activeJobId, error);
            }
            throw new IllegalStateException("Unable to produce ROWID chunks: "
                    + error.getMessage(), error);
        }
        try (Connection repository = openRepository()) {
            saveMetric(repository, prepared.step().jobStepId(), "CHUNKS_TOTAL",
                    chunkNumber - 1L, "CHUNKS");
            repository.commit();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to finalize chunk metrics: "
                    + error.getMessage(), error);
        }
        requireExpectedRows(prepared.expected(), rowsProcessed);
        return rowsProcessed;
    }

    private final class Work {
        private final ExecutableStep step;
        private final Operation operation;
        private final ChunkRange range;
        private final long expected;
        private final boolean chunked;
        private final PrimaryKey primaryKey;

        private Work(ExecutableStep step, Operation operation, ChunkRange range,
                long expected, boolean chunked, PrimaryKey primaryKey) {
            this.step = step;
            this.operation = operation;
            this.range = range;
            this.expected = expected;
            this.chunked = chunked;
            this.primaryKey = primaryKey;
        }

        private WorkResult execute() {
            checkCancellation();
            try (Connection repository = openRepository(); Connection target = openTarget()) {
                long chunkId = startChunk(repository, step.jobStepId(), range);
                boolean commitAttempted = "TRUNCATE".equals(operation.type());
                try {
                    checkCancellation();
                    long rows = chunked ? executeChunk(target, step.generatedCode(), range,
                            primaryKey)
                            : executeDirect(target, step.generatedCode(), operation, expected);
                    checkCancellation();
                    commitAttempted = true;
                    target.commit();
                    completeChunk(repository, chunkId, rows);
                    return new WorkResult(step.jobStepId(), rows);
                } catch (SQLException | RuntimeException error) {
                    boolean rolledBack = rollback(target, error);
                    if (commitAttempted) {
                        throw new IllegalStateException("Chunk " + chunkId
                                + " has an uncertain commit outcome. Automatic retry is blocked; "
                                + "reconcile target data and the chunk journal before resuming.", error);
                    }
                    if (!rolledBack) {
                        throw new IllegalStateException("Chunk " + chunkId
                                + " rollback was not confirmed. Automatic retry is blocked.", error);
                    }
                    if (cancellationRequested.get()) {
                        cancelChunk(repository, chunkId);
                        if (error instanceof JobExecutionCancelledException cancelled) {
                            throw cancelled;
                        }
                        throw new JobExecutionCancelledException(activeJobId, error);
                    }
                    failChunk(repository, chunkId, error);
                    throw new IllegalStateException("Step " + step.stepNumber() + ", chunk "
                            + range.number() + " failed: " + error.getMessage(), error);
                }
            } catch (SQLException error) {
                if (cancellationRequested.get()) {
                    throw new JobExecutionCancelledException(activeJobId, error);
                }
                throw new IllegalStateException("Unable to open parallel worker connections: "
                        + error.getMessage(), error);
            }
        }
    }

    private long executeChunk(Connection target, String baseSql, ChunkRange range,
            PrimaryKey primaryKey)
            throws SQLException {
        String predicate = switch (targetTechnology) {
            case "POSTGRESQL", "SQLSERVER" -> "(? IS NULL OR " + primaryKey.quotedColumn()
                    + " > " + boundaryParameter(targetTechnology, primaryKey.castType()) + ") AND "
                    + primaryKey.quotedColumn() + " <= "
                    + boundaryParameter(targetTechnology, primaryKey.castType());
            default -> "(? IS NULL OR ROWID > CHARTOROWID(?)) AND ROWID <= CHARTOROWID(?)";
        };
        String sql = baseSql + System.lineSeparator() + "WHERE " + predicate;
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            bindNullable(statement, 1, range.start());
            bindNullable(statement, 2, range.start());
            statement.setString(3, range.end());
            return executeTrackedUpdate(statement);
        }
    }

    private long executeDirect(Connection target, String sql, Operation operation,
            long expected) throws SQLException {
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            int affected = executeTrackedUpdate(statement);
            return "TRUNCATE".equals(operation.type()) ? expected : affected;
        }
    }

    private String findLastBoundary(Connection target, String table, PrimaryKey primaryKey,
            String previous)
            throws SQLException {
        String sql;
        if ("POSTGRESQL".equals(targetTechnology)) {
            sql = "SELECT CAST(" + primaryKey.quotedColumn() + " AS text) FROM " + table
                    + " WHERE (? IS NULL OR " + primaryKey.quotedColumn()
                    + " > CAST(? AS " + primaryKey.castType() + ")) ORDER BY "
                    + primaryKey.quotedColumn() + " LIMIT ?";
        } else if ("SQLSERVER".equals(targetTechnology)) {
            sql = "SELECT TOP (?) " + sqlServerBoundaryExpression(primaryKey.quotedColumn(),
                    primaryKey.castType()) + " FROM " + table + " WHERE (? IS NULL OR "
                    + primaryKey.quotedColumn() + " > "
                    + boundaryParameter(targetTechnology, primaryKey.castType())
                    + ") ORDER BY " + primaryKey.quotedColumn();
        } else {
            sql = "SELECT ROWIDTOCHAR(rid) FROM (SELECT ROWID rid FROM " + table
                    + " WHERE (? IS NULL OR ROWID > CHARTOROWID(?))"
                    + " ORDER BY ROWID FETCH FIRST ? ROWS ONLY)"
                    + " ORDER BY rid DESC FETCH FIRST 1 ROW ONLY";
        }
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            if ("SQLSERVER".equals(targetTechnology)) {
                statement.setInt(1, chunkSize);
                bindNullable(statement, 2, previous);
                bindNullable(statement, 3, previous);
            } else {
                bindNullable(statement, 1, previous);
                bindNullable(statement, 2, previous);
                statement.setInt(3, chunkSize);
            }
            try (ResultSet row = executeTrackedQuery(statement)) {
                String last = null;
                while (row.next()) last = row.getString(1);
                return last;
            }
        }
    }

    private long expectedRows(Connection target, String sql, Operation operation)
            throws SQLException {
        int where = findTopLevelWhere(sql, operation.targetEnd());
        String countSql = "SELECT COUNT(*) FROM " + operation.table()
                + (where < 0 ? "" : " " + sql.substring(where));
        try (PreparedStatement statement = target.prepareStatement(countSql);
                ResultSet row = executeTrackedQuery(statement)) {
            row.next();
            return row.getLong(1);
        }
    }

    private long estimatedRows(Connection target, String table) throws SQLException {
        TableParts parts = tableParts(table);
        if ("POSTGRESQL".equals(targetTechnology)) {
            try (PreparedStatement statement = target.prepareStatement("""
                    SELECT c.reltuples::bigint FROM pg_class c
                      JOIN pg_namespace n ON n.oid=c.relnamespace
                     WHERE n.nspname=COALESCE(?,current_schema()) AND c.relname=?
                    """)) {
                bindNullable(statement, 1, parts.schema());
                statement.setString(2, parts.table());
                try (ResultSet row = executeTrackedQuery(statement)) {
                    return row.next() ? row.getLong(1) : -1;
                }
            }
        }
        if ("SQLSERVER".equals(targetTechnology)) {
            try (PreparedStatement statement = target.prepareStatement("""
                    SELECT SUM(p.rows) FROM sys.tables t
                      JOIN sys.schemas s ON s.schema_id=t.schema_id
                      JOIN sys.partitions p ON p.object_id=t.object_id AND p.index_id IN(0,1)
                     WHERE s.name=COALESCE(?,SCHEMA_NAME()) AND t.name=?
                    """)) {
                bindNullable(statement, 1, parts.schema());
                statement.setString(2, parts.table());
                try (ResultSet row = executeTrackedQuery(statement)) {
                    if (!row.next()) return -1;
                    long value = row.getLong(1);
                    return row.wasNull() ? -1 : value;
                }
            }
        }
        String sql = parts.schema() != null
                ? "SELECT num_rows FROM all_tables WHERE owner=? AND table_name=?"
                : "SELECT num_rows FROM user_tables WHERE table_name=?";
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            int index = 1;
            if (parts.schema() != null) statement.setString(index++,
                    parts.schema().toUpperCase(Locale.ROOT));
            statement.setString(index, parts.table().toUpperCase(Locale.ROOT));
            try (ResultSet row = executeTrackedQuery(statement)) {
                if (!row.next()) return -1;
                long value = row.getLong(1);
                return row.wasNull() ? -1 : value;
            }
        }
    }

    private long boundedRowCount(Connection target, String table, long limit)
            throws SQLException {
        String sql = switch (targetTechnology) {
            case "POSTGRESQL" -> "SELECT COUNT(*) FROM (SELECT 1 FROM " + table
                    + " LIMIT ?) privonym_bounded_rows";
            case "SQLSERVER" -> "SELECT COUNT(*) FROM (SELECT TOP (?) 1 AS row_marker FROM "
                    + table + ") privonym_bounded_rows";
            default -> "SELECT COUNT(*) FROM (SELECT 1 FROM " + table + " WHERE ROWNUM<=?)";
        };
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            statement.setLong(1, limit);
            try (ResultSet row = executeTrackedQuery(statement)) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private PrimaryKey primaryKey(Connection target, String table) throws SQLException {
        TableParts parts = tableParts(table);
        if ("POSTGRESQL".equals(targetTechnology)) {
            String sql = """
                    SELECT a.attname,format_type(a.atttypid,a.atttypmod)
                      FROM pg_index i
                      JOIN pg_class c ON c.oid=i.indrelid
                      JOIN pg_namespace n ON n.oid=c.relnamespace
                      JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum,position)
                        ON k.position<=i.indnkeyatts
                      JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=k.attnum
                     WHERE i.indisprimary AND n.nspname=COALESCE(?,current_schema())
                       AND c.relname=? ORDER BY k.position
                    """;
            try (PreparedStatement statement = target.prepareStatement(sql)) {
                bindNullable(statement, 1, parts.schema());
                statement.setString(2, parts.table());
                try (ResultSet rows = executeTrackedQuery(statement)) {
                    if (!rows.next()) return null;
                    String column = rows.getString(1);
                    String type = safePostgresqlCastType(rows.getString(2));
                    if (rows.next() || type == null) return null;
                    return new PrimaryKey(quoteIdentifier(column, "POSTGRESQL"), type);
                }
            }
        }
        if ("SQLSERVER".equals(targetTechnology)) {
            String sql = """
                    SELECT c.name,ty.name,c.max_length,c.precision,c.scale
                      FROM sys.indexes i
                      JOIN sys.index_columns ic ON ic.object_id=i.object_id
                                               AND ic.index_id=i.index_id
                      JOIN sys.columns c ON c.object_id=ic.object_id
                                        AND c.column_id=ic.column_id
                      JOIN sys.types ty ON ty.user_type_id=c.user_type_id
                      JOIN sys.tables t ON t.object_id=i.object_id
                      JOIN sys.schemas s ON s.schema_id=t.schema_id
                     WHERE i.is_primary_key=1 AND ic.key_ordinal>0
                       AND s.name=COALESCE(?,SCHEMA_NAME())
                       AND t.name=? ORDER BY ic.key_ordinal
                    """;
            try (PreparedStatement statement = target.prepareStatement(sql)) {
                bindNullable(statement, 1, parts.schema());
                statement.setString(2, parts.table());
                try (ResultSet rows = executeTrackedQuery(statement)) {
                    if (!rows.next()) return null;
                    String column = rows.getString(1);
                    String type = sqlServerCastType(rows.getString(2), rows.getInt(3),
                            rows.getInt(4), rows.getInt(5));
                    if (rows.next() || type == null) return null;
                    return new PrimaryKey(quoteIdentifier(column, "SQLSERVER"), type);
                }
            }
        }
        return null;
    }

    private static String safePostgresqlCastType(String type) {
        if (type == null || !type.matches("[A-Za-z0-9_ .,()\\[\\]]+")) return null;
        return type;
    }

    private static String sqlServerCastType(String type, int maxLength, int precision,
            int scale) {
        if (type == null) return null;
        String normalized = type.toLowerCase(Locale.ROOT);
        if (List.of("varchar", "char", "varbinary", "binary").contains(normalized)) {
            return normalized + "(" + (maxLength < 0 ? "max" : maxLength) + ")";
        }
        if (List.of("nvarchar", "nchar").contains(normalized)) {
            return normalized + "(" + (maxLength < 0 ? "max" : maxLength / 2) + ")";
        }
        if (List.of("decimal", "numeric").contains(normalized)) {
            return normalized + "(" + precision + "," + scale + ")";
        }
        if (List.of("datetime2", "datetimeoffset", "time").contains(normalized)) {
            return normalized + "(" + scale + ")";
        }
        return List.of("tinyint", "smallint", "int", "bigint", "bit", "money",
                "smallmoney", "date", "datetime", "smalldatetime", "uniqueidentifier",
                "float", "real").contains(normalized) ? normalized : null;
    }

    private static boolean updatesPrimaryKey(String sql, Operation operation,
            PrimaryKey primaryKey) {
        return updatesPrimaryKey(sql.substring(operation.targetEnd()), primaryKey.quotedColumn());
    }

    static boolean updatesPrimaryKey(String assignments, String quotedColumn) {
        String name = unquoteIdentifier(quotedColumn);
        String identifiers = Pattern.quote("\"" + name.replace("\"", "\"\"") + "\"")
                + "|" + Pattern.quote("[" + name.replace("]", "]]") + "]")
                + "|" + Pattern.quote(name);
        return Pattern.compile("(?is)(?:^|,)\\s*(?:" + identifiers + ")"
                + "\\s*=").matcher(assignments).find();
    }

    static String sqlServerBoundaryExpression(String column, String type) {
        String normalized = type.toLowerCase(Locale.ROOT);
        int style = normalized.equals("float") || normalized.equals("real") ? 3
                : normalized.equals("money") || normalized.equals("smallmoney")
                        || normalized.startsWith("binary(") || normalized.startsWith("varbinary(") ? 2
                : normalized.startsWith("datetimeoffset") ? 127
                : normalized.equals("date") || normalized.startsWith("datetime")
                        || normalized.equals("smalldatetime") || normalized.startsWith("time(") ? 126 : 0;
        return "CONVERT(nvarchar(max)," + column + "," + style + ")";
    }

    static String boundaryParameter(String technology, String type) {
        if ("SQLSERVER".equals(technology)
                && (type.startsWith("binary(") || type.startsWith("varbinary("))) {
            return "CONVERT(" + type + ",?,2)";
        }
        return "CAST(? AS " + type + ")";
    }

    static void requireExpectedRows(long expected, long processed) {
        if (expected != processed) throw new IllegalStateException(
                "Chunk row-count mismatch: expected=" + expected + ", processed=" + processed);
    }

    private ResultSet executeTrackedQuery(PreparedStatement statement) throws SQLException {
        statement.setQueryTimeout(queryTimeoutSeconds());
        activeStatements.add(statement);
        try {
            checkCancellation();
            return statement.executeQuery();
        } finally {
            activeStatements.remove(statement);
        }
    }

    private int executeTrackedUpdate(PreparedStatement statement) throws SQLException {
        statement.setQueryTimeout(queryTimeoutSeconds());
        activeStatements.add(statement);
        try {
            checkCancellation();
            int affected = statement.executeUpdate();
            checkCancellation();
            return affected;
        } finally {
            activeStatements.remove(statement);
        }
    }

    private static Map<Integer, SuccessfulChunk> successfulChunks(Connection repository,
            long stepId) throws SQLException {
        requireReconciledChunks(repository, stepId);
        Map<Integer, SuccessfulChunk> result = new LinkedHashMap<>();
        try (PreparedStatement statement = repository.prepareStatement("""
                SELECT chunk_number,end_rowid,rows_processed FROM job_chunk
                 WHERE job_step_id=? AND status='SUCCEEDED' ORDER BY chunk_number
                """)) {
            statement.setLong(1, stepId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.put(row.getInt(1),
                        new SuccessfulChunk(row.getString(2), row.getLong(3)));
            }
        }
        return result;
    }

    private static long startChunk(Connection repository, long stepId, ChunkRange range)
            throws SQLException {
        try (PreparedStatement delete = repository.prepareStatement("""
                DELETE FROM job_chunk WHERE job_step_id=? AND chunk_number=?
                  AND (status='FAILED' OR (status='CANCELLED'
                       AND error_message LIKE 'PRIVONYM-ROLLBACK-CONFIRMED:%'))
                """)) {
            delete.setLong(1, stepId);
            delete.setInt(2, range.number());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = repository.prepareStatement("""
                INSERT INTO job_chunk(job_step_id,chunk_number,status,start_rowid,end_rowid,
                    rows_processed,started_at) VALUES (?,?,'RUNNING',?,?,0,SYSTIMESTAMP)
                """)) {
            insert.setLong(1, stepId);
            insert.setInt(2, range.number());
            if (range.start() == null) insert.setNull(3, Types.VARCHAR);
            else insert.setString(3, range.start());
            insert.setString(4, range.end());
            insert.executeUpdate();
        }
        try (PreparedStatement select = repository.prepareStatement("""
                SELECT chunk_id FROM job_chunk WHERE job_step_id=? AND chunk_number=?
                """)) {
            select.setLong(1, stepId);
            select.setInt(2, range.number());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                long id = row.getLong(1);
                repository.commit();
                return id;
            }
        }
    }

    static void requireReconciledChunks(Connection repository, long stepId) throws SQLException {
        try (PreparedStatement statement = repository.prepareStatement("""
                SELECT COUNT(*) FROM job_chunk WHERE job_step_id=?
                  AND (status NOT IN ('SUCCEEDED','FAILED')
                       AND NOT (status='CANCELLED'
                                AND COALESCE(error_message,'UNKNOWN')
                                    LIKE 'PRIVONYM-ROLLBACK-CONFIRMED:%'))
                """)) {
            statement.setLong(1, stepId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                if (row.getLong(1) != 0) throw new IllegalStateException(
                        "Step " + stepId + " contains unreconciled chunks. Automatic retry is blocked; "
                        + "verify target data before changing the chunk journal.");
            }
        }
    }

    private void completeChunk(Connection repository, long chunkId, long rows)
            throws SQLException {
        try (PreparedStatement statement = repository.prepareStatement("""
                UPDATE job_chunk SET status='SUCCEEDED',rows_processed=?,ended_at=SYSTIMESTAMP
                 WHERE chunk_id=? AND status IN ('RUNNING','CANCELLED')
                """)) {
            statement.setLong(1, rows);
            statement.setLong(2, chunkId);
            if (statement.executeUpdate() != 1) {
                String status = chunkStatus(repository, chunkId);
                if ("SUCCEEDED".equals(status)) return;
                if ("CANCELLED".equals(status) || repositoryJobCancelled(repository)) {
                    cancellationRequested.set(true);
                    throw new JobExecutionCancelledException(activeJobId);
                }
                throw new SQLException("Chunk " + chunkId + " is no longer RUNNING"
                        + (status == null ? " (journal row missing)" : " (status=" + status + ")"));
            }
        }
        repository.commit();
    }

    private static String chunkStatus(Connection repository, long chunkId) throws SQLException {
        try (PreparedStatement statement = repository.prepareStatement(
                "SELECT status FROM job_chunk WHERE chunk_id=?")) {
            statement.setLong(1, chunkId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private boolean repositoryJobCancelled(Connection repository) throws SQLException {
        try (PreparedStatement statement = repository.prepareStatement(
                "SELECT status FROM anonymization_job WHERE job_id=?")) {
            statement.setLong(1, activeJobId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && List.of("CANCELLING", "CANCELLED").contains(row.getString(1));
            }
        }
    }

    private static void failChunk(Connection repository, long chunkId, Throwable error) {
        try (PreparedStatement statement = repository.prepareStatement("""
                UPDATE job_chunk SET status='FAILED',error_message=?,ended_at=SYSTIMESTAMP
                 WHERE chunk_id=? AND status='RUNNING'
                """)) {
            String message = String.valueOf(error.getMessage());
            statement.setString(1, message.substring(0, Math.min(message.length(), 4000)));
            statement.setLong(2, chunkId);
            statement.executeUpdate();
            repository.commit();
        } catch (SQLException journalError) {
            error.addSuppressed(journalError);
            rollback(repository, journalError);
        }
    }

    private static void cancelChunk(Connection repository, long chunkId) {
        try (PreparedStatement statement = repository.prepareStatement("""
                UPDATE job_chunk SET status='CANCELLED',
                       error_message='PRIVONYM-ROLLBACK-CONFIRMED: Cancelled by operator',ended_at=SYSTIMESTAMP
                 WHERE chunk_id=? AND status IN ('RUNNING','CANCELLED')
                """)) {
            statement.setLong(1, chunkId);
            statement.executeUpdate();
            repository.commit();
        } catch (SQLException error) {
            rollback(repository, error);
        }
    }

    private static void saveMetric(Connection repository, long stepId, String name,
            long value, String unit) throws SQLException {
        try (PreparedStatement delete = repository.prepareStatement(
                "DELETE FROM job_metric WHERE job_step_id=? AND metric_name=?")) {
            delete.setLong(1, stepId);
            delete.setString(2, name);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = repository.prepareStatement("""
                INSERT INTO job_metric(job_id,job_step_id,metric_name,metric_value,metric_unit)
                SELECT job_id,?,?,?,? FROM job_step WHERE job_step_id=?
                """)) {
            insert.setLong(1, stepId);
            insert.setString(2, name);
            insert.setLong(3, value);
            insert.setString(4, unit);
            insert.setLong(5, stepId);
            if (insert.executeUpdate() != 1) throw new SQLException("Execution step was not found");
        }
    }

    static int queryTimeoutSeconds() {
        int value = Integer.getInteger("privonym.jdbc.queryTimeoutSeconds", 0);
        if (value < 0 || value > 86400) throw new IllegalArgumentException(
                "privonym.jdbc.queryTimeoutSeconds must be between 0 and 86400 (0 disables the statement timeout)");
        return value;
    }

    private Connection openRepository() throws SQLException {
        Connection connection = DriverManager.getConnection(repositoryUrl, repositoryUser,
                new String(repositoryPassword));
        connection.setAutoCommit(false);
        return connection;
    }

    private Connection openTarget() throws SQLException {
        Connection connection = DriverManager.getConnection(targetUrl, targetUser,
                new String(targetPassword));
        connection.setAutoCommit(false);
        if ("SQLSERVER".equals(targetTechnology)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET QUOTED_IDENTIFIER ON");
                statement.execute("SET XACT_ABORT ON");
            }
        }
        return connection;
    }

    private static void validateChecksum(ExecutableStep step) {
        if (!SqlChecksum.sha256(step.generatedCode()).equals(step.checksum().value())) {
            throw new IllegalStateException("SQL checksum mismatch at step " + step.stepNumber());
        }
        if (step.generatedCode().contains(";")) {
            throw new IllegalStateException("One generated statement per step is required");
        }
    }

    private static Operation operation(String sql) {
        Matcher update = UPDATE.matcher(sql);
        if (update.find()) return new Operation("UPDATE", update.group(1), update.end());
        Matcher delete = DELETE.matcher(sql);
        if (delete.find()) return new Operation("DELETE", delete.group(1), delete.end());
        Matcher truncate = TRUNCATE.matcher(sql);
        if (truncate.matches()) return new Operation("TRUNCATE", truncate.group(1), truncate.end(1));
        throw new IllegalStateException("Parallel mode accepts UPDATE, DELETE or TRUNCATE steps");
    }

    static String technologyFromJdbcUrl(String jdbcUrl) {
        String url = Objects.requireNonNull(jdbcUrl, "jdbcUrl").toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:postgresql:")) return "POSTGRESQL";
        if (url.startsWith("jdbc:sqlserver:")) return "SQLSERVER";
        if (url.startsWith("jdbc:oracle:")) return "ORACLE";
        throw new IllegalArgumentException("Unsupported target JDBC URL: " + jdbcUrl);
    }

    private static TableParts tableParts(String qualifiedTable) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean doubleQuoted = false;
        boolean bracketed = false;
        for (int index = 0; index < qualifiedTable.length(); index++) {
            char value = qualifiedTable.charAt(index);
            if (value == '\"' && !bracketed) doubleQuoted = !doubleQuoted;
            else if (value == '[' && !doubleQuoted) bracketed = true;
            else if (value == ']' && bracketed) bracketed = false;
            if (value == '.' && !doubleQuoted && !bracketed) {
                parts.add(unquoteIdentifier(current.toString().trim()));
                current.setLength(0);
            } else current.append(value);
        }
        parts.add(unquoteIdentifier(current.toString().trim()));
        if (parts.size() == 1) return new TableParts(null, parts.get(0));
        if (parts.size() == 2) return new TableParts(parts.get(0), parts.get(1));
        throw new IllegalArgumentException("Unsupported qualified table name: " + qualifiedTable);
    }

    private static String unquoteIdentifier(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        if (value.length() >= 2 && value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1).replace("]]", "]");
        }
        return value;
    }

    private static String quoteIdentifier(String value, String technology) {
        return "SQLSERVER".equals(technology)
                ? "[" + value.replace("]", "]]" ) + "]"
                : "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static boolean chunkable(String sql, Operation operation) {
        String upper = sql.toUpperCase(Locale.ROOT);
        return "UPDATE".equals(operation.type())
                && findTopLevelWhere(sql, operation.targetEnd()) < 0
                && !upper.contains("DBMS_RANDOM") && !upper.contains("SYS_GUID");
    }

    private static int findTopLevelWhere(String sql, int start) {
        int depth = 0;
        boolean quoted = false;
        for (int index = start; index <= sql.length() - 5; index++) {
            char current = sql.charAt(index);
            if (current == '\'') {
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                quoted = !quoted;
            }
            if (quoted) continue;
            if (current == '(') depth++;
            else if (current == ')') depth--;
            else if (depth == 0 && sql.regionMatches(true, index, "WHERE", 0, 5)
                    && (index == 0 || Character.isWhitespace(sql.charAt(index - 1)))
                    && (index + 5 == sql.length() || Character.isWhitespace(sql.charAt(index + 5)))) {
                return index;
            }
        }
        return -1;
    }

    private static void bindNullable(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static boolean rollback(Connection connection, Throwable error) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException rollbackError) {
            error.addSuppressed(rollbackError);
            return false;
        }
    }

    /** Requests cancellation of the executor running in this server process. */
    public static boolean cancel(long jobId) {
        OracleParallelPlanExecutor executor = ACTIVE_EXECUTIONS.get(jobId);
        if (executor == null) return false;
        executor.requestCancellation();
        return true;
    }

    private void requestCancellation() {
        cancellationRequested.set(true);
        for (Statement statement : activeStatements) {
            try {
                statement.cancel();
            } catch (SQLException ignored) {
                // The worker will still observe the cancellation flag before commit.
            }
        }
    }

    private void checkCancellation() {
        if (cancellationRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new JobExecutionCancelledException(activeJobId);
        }
    }

    private static Throwable executionCause(ExecutionException error) {
        Throwable cause = error.getCause();
        while (cause instanceof ExecutionException nested && nested.getCause() != null) {
            cause = nested.getCause();
        }
        return cause == null ? error : cause;
    }

    private static void shutdownAndAwait(ExecutorService executor) {
        if (executor == null) return;
        executor.shutdownNow();
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                interrupted = true;
                executor.shutdownNow();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public void close() {
        if (activeJobId != 0) requestCancellation();
        Arrays.fill(repositoryPassword, '\0');
        Arrays.fill(targetPassword, '\0');
    }

    private record Operation(String type, String table, int targetEnd) { }
    private record ChunkRange(int number, String start, String end) { }
    private record SuccessfulChunk(String end, long rows) { }
    private record WorkResult(long stepId, long rows) { }
    private record PreparedStep(ExecutableStep step, Operation operation, long expected,
            boolean chunked, Map<Integer, SuccessfulChunk> completed,
            PrimaryKey primaryKey) { }
    private record PrimaryKey(String quotedColumn, String castType) { }
    private record TableParts(String schema, String table) { }
}
