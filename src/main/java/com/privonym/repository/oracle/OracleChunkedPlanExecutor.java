package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.SqlChecksum;
import com.privonym.application.port.out.TargetPlanExecutor;
import com.privonym.application.service.ExecutablePlan;
import com.privonym.application.service.ExecutableStep;
import com.privonym.application.service.ExecutionOutcome;
import com.privonym.application.service.StepExecutionResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OracleChunkedPlanExecutor implements TargetPlanExecutor {
    private static final Pattern UPDATE_TABLE = Pattern.compile(
            "(?is)^UPDATE\\s+(\"[A-Z][A-Z0-9_$#]{0,127}\"\\."
                    + "\"[A-Z][A-Z0-9_$#]{0,127}\")\\s+SET\\s+");
    private final Connection repository;
    private final Connection target;
    private final int chunkSize;

    public OracleChunkedPlanExecutor(Connection repository, Connection target, int chunkSize) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.target = Objects.requireNonNull(target, "target");
        if (chunkSize < 1 || chunkSize > 1_000_000) {
            throw new IllegalArgumentException("Invalid chunk size: " + chunkSize);
        }
        this.chunkSize = chunkSize;
    }

    @Override
    public ExecutionOutcome execute(ExecutablePlan plan) {
        List<StepExecutionResult> results = new ArrayList<>();
        for (ExecutableStep step : plan.steps()) {
            results.add(executeStep(step));
        }
        return new ExecutionOutcome(results);
    }

    private StepExecutionResult executeStep(ExecutableStep step) {
        validate(step);
        String table = extractTable(step.generatedCode());
        ChunkCursor cursor = loadCursor(step.jobStepId());
        long totalRows = cursor.rowsProcessed();
        String previous = cursor.lastRowId();
        int chunkNumber = cursor.nextChunkNumber();

        while (true) {
            String last = findLastRowId(table, previous);
            if (last == null) {
                return new StepExecutionResult(step.jobStepId(), totalRows);
            }
            long chunkId = startChunk(step.jobStepId(), chunkNumber, previous, last);
            try {
                int rows = executeChunk(step.generatedCode(), previous, last);
                target.commit();
                completeChunk(chunkId, rows);
                totalRows += rows;
                previous = last;
                chunkNumber++;
            } catch (SQLException | RuntimeException error) {
                rollbackTarget(error);
                failChunk(chunkId, error);
                throw new IllegalStateException("CHUNKED execution failed at chunk "
                        + chunkNumber + ": " + error.getMessage(), error);
            }
        }
    }

    private String findLastRowId(String table, String previous) {
        String sql = "SELECT ROWIDTOCHAR(rid) FROM (SELECT ROWID rid FROM " + table
                + " WHERE (? IS NULL OR ROWID > CHARTOROWID(?))"
                + " ORDER BY ROWID FETCH FIRST ? ROWS ONLY)"
                + " ORDER BY rid DESC FETCH FIRST 1 ROW ONLY";
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            bindNullableRowId(statement, 1, previous);
            bindNullableRowId(statement, 2, previous);
            statement.setInt(3, chunkSize);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to select next ROWID chunk: "
                    + error.getMessage(), error);
        }
    }

    private int executeChunk(String baseSql, String previous, String last)
            throws SQLException {
        String sql = baseSql + System.lineSeparator()
                + "WHERE (? IS NULL OR ROWID > CHARTOROWID(?))"
                + " AND ROWID <= CHARTOROWID(?)";
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            bindNullableRowId(statement, 1, previous);
            bindNullableRowId(statement, 2, previous);
            statement.setString(3, last);
            return statement.executeUpdate();
        }
    }

    private ChunkCursor loadCursor(long stepId) {
        String sql = """
                SELECT COALESCE(MAX(chunk_number), 0) + 1 next_chunk,
                       COALESCE(SUM(rows_processed), 0) processed
                  FROM job_chunk WHERE job_step_id = ? AND status = 'SUCCEEDED'
                """;
        try (PreparedStatement statement = repository.prepareStatement(sql)) {
            statement.setLong(1, stepId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                int next = row.getInt("next_chunk");
                String last = null;
                if (next > 1) {
                    last = loadLastSuccessfulRowId(stepId);
                }
                return new ChunkCursor(next, last, row.getLong("processed"));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to load chunk checkpoint: "
                    + error.getMessage(), error);
        }
    }

    private String loadLastSuccessfulRowId(long stepId) throws SQLException {
        try (PreparedStatement statement = repository.prepareStatement("""
                SELECT end_rowid FROM job_chunk WHERE job_step_id = ? AND status = 'SUCCEEDED'
                 ORDER BY chunk_number DESC FETCH FIRST 1 ROW ONLY
                """)) {
            statement.setLong(1, stepId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private long startChunk(long stepId, int number, String start, String end) {
        try {
            try (PreparedStatement delete = repository.prepareStatement("""
                    DELETE FROM job_chunk WHERE job_step_id = ? AND chunk_number = ?
                      AND status = 'FAILED'
                    """)) {
                delete.setLong(1, stepId);
                delete.setInt(2, number);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = repository.prepareStatement("""
                    INSERT INTO job_chunk
                        (job_step_id, chunk_number, status, start_rowid, end_rowid,
                         started_at, rows_processed)
                    VALUES (?, ?, 'RUNNING', ?, ?, SYSTIMESTAMP, 0)
                    """)) {
                insert.setLong(1, stepId);
                insert.setInt(2, number);
                if (start == null) {
                    insert.setNull(3, Types.VARCHAR);
                } else {
                    insert.setString(3, start);
                }
                insert.setString(4, end);
                insert.executeUpdate();
                try (PreparedStatement select = repository.prepareStatement("""
                        SELECT chunk_id FROM job_chunk
                         WHERE job_step_id = ? AND chunk_number = ?
                        """)) {
                    select.setLong(1, stepId);
                    select.setInt(2, number);
                    try (ResultSet returned = select.executeQuery()) {
                        returned.next();
                        long id = returned.getLong(1);
                        repository.commit();
                        return id;
                    }
                }
            }
        } catch (SQLException error) {
            rollbackRepository(error);
            throw new IllegalStateException("Unable to start chunk checkpoint: "
                    + error.getMessage(), error);
        }
    }

    private void completeChunk(long chunkId, int rows) throws SQLException {
        try (PreparedStatement statement = repository.prepareStatement("""
                UPDATE job_chunk SET status = 'SUCCEEDED', rows_processed = ?,
                       ended_at = SYSTIMESTAMP WHERE chunk_id = ? AND status = 'RUNNING'
                """)) {
            statement.setInt(1, rows);
            statement.setLong(2, chunkId);
            statement.executeUpdate();
            repository.commit();
        }
    }

    private void failChunk(long chunkId, Throwable error) {
        try (PreparedStatement statement = repository.prepareStatement("""
                UPDATE job_chunk SET status = 'FAILED', ended_at = SYSTIMESTAMP,
                       error_message = ? WHERE chunk_id = ? AND status = 'RUNNING'
                """)) {
            String message = String.valueOf(error.getMessage());
            statement.setString(1, message.substring(0, Math.min(4000, message.length())));
            statement.setLong(2, chunkId);
            statement.executeUpdate();
            repository.commit();
        } catch (SQLException journalError) {
            error.addSuppressed(journalError);
            rollbackRepository(journalError);
        }
    }

    private static void validate(ExecutableStep step) {
        String code = step.generatedCode().strip();
        if (!SqlChecksum.sha256(step.generatedCode()).equals(step.checksum().value())) {
            throw new IllegalStateException("SQL checksum mismatch at step " + step.stepNumber());
        }
        String upper = code.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("UPDATE ") || code.contains(";")
                || upper.contains("DBMS_RANDOM") || upper.contains("SYS_GUID")
                || upper.contains("\nWHERE ")) {
            throw new IllegalStateException(
                    "CHUNKED mode requires one idempotent, unfiltered UPDATE statement");
        }
    }

    private static String extractTable(String sql) {
        Matcher matcher = UPDATE_TABLE.matcher(sql.strip());
        if (!matcher.find()) {
            throw new IllegalStateException("Unable to identify table in generated UPDATE");
        }
        return matcher.group(1);
    }

    private static void bindNullableRowId(PreparedStatement statement, int index,
            String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private void rollbackTarget(Throwable error) {
        try {
            target.rollback();
        } catch (SQLException rollbackError) {
            error.addSuppressed(rollbackError);
        }
    }

    private void rollbackRepository(Throwable error) {
        try {
            repository.rollback();
        } catch (SQLException rollbackError) {
            error.addSuppressed(rollbackError);
        }
    }

    private record ChunkCursor(int nextChunkNumber, String lastRowId, long rowsProcessed) {
    }
}
