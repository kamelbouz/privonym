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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Oracle DIRECT executor that captures the population expected by every table step. */
public final class OracleMeasuredPlanExecutor implements TargetPlanExecutor {
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)^\\s*UPDATE\\s+((?:\\\"[^\\\"]+\\\"|[A-Za-z0-9_$#]+)(?:\\s*\\.\\s*(?:\\\"[^\\\"]+\\\"|[A-Za-z0-9_$#]+))?)\\s+SET\\s+");
    private static final Pattern DELETE = Pattern.compile(
            "(?is)^\\s*DELETE\\s+FROM\\s+((?:\\\"[^\\\"]+\\\"|[A-Za-z0-9_$#]+)(?:\\s*\\.\\s*(?:\\\"[^\\\"]+\\\"|[A-Za-z0-9_$#]+))?)(?:\\s+WHERE\\s+.+)?\\s*$");
    private static final Pattern TRUNCATE = Pattern.compile(
            "(?is)^\\s*TRUNCATE\\s+TABLE\\s+((?:\\\"[^\\\"]+\\\"|[A-Za-z0-9_$#]+)(?:\\s*\\.\\s*(?:\\\"[^\\\"]+\\\"|[A-Za-z0-9_$#]+))?)\\s*$");
    private final Connection repository;
    private final Connection target;

    public OracleMeasuredPlanExecutor(Connection repository, Connection target) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public ExecutionOutcome execute(ExecutablePlan plan) {
        List<StepExecutionResult> results = new ArrayList<>();
        try {
            List<RelationshipConstraint> constraints = relationshipConstraints(
                    plan.jobId().value());
            deferOrDisableConstraints(constraints);
            Map<Long,Integer> groups = dependencyGroups(plan.jobId().value());
            List<ExecutableStep> ordered = new ArrayList<>(plan.steps());
            ordered.sort((left,right) -> {
                int byGroup = Integer.compare(groups.getOrDefault(left.jobStepId(), 0),
                        groups.getOrDefault(right.jobStepId(), 0));
                return byGroup != 0 ? byGroup
                        : Integer.compare(left.stepNumber(), right.stepNumber());
            });
            for (ExecutableStep step : ordered) {
                validate(step);
                Operation operation = operation(step.generatedCode());
                long expected = expectedRows(step.generatedCode(), operation);
                TargetRowCountEvidence.capture(repository,target,step.jobStepId(),operation.table(),operation.type(),
                        expected,findTopLevelWhere(step.generatedCode(),operation.targetEnd())>=0,false);
                saveExpectedRows(step.jobStepId(), expected);
                try (PreparedStatement statement = target.prepareStatement(step.generatedCode())) {
                    int affected = statement.executeUpdate();
                    long processed = operation.type().equals("TRUNCATE") ? expected : affected;
                    results.add(new StepExecutionResult(step.jobStepId(), processed));
                }
            }
            restoreConstraints(constraints);
            target.commit();
            return new ExecutionOutcome(results);
        } catch (SQLException | RuntimeException error) {
            try { target.rollback(); } catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
            throw new IllegalStateException("DIRECT target execution was rolled back: " + error.getMessage(), error);
        }
    }

    private List<RelationshipConstraint> relationshipConstraints(long jobId)
            throws SQLException {
        List<RelationshipConstraint> constraints = new ArrayList<>();
        String sql = """
                SELECT DISTINCT c.technology_code,cr.constraint_name,cs.schema_name,ct.table_name
                  FROM anonymization_job j
                  JOIN data_source ds ON ds.data_source_id=j.data_source_id
                  JOIN connector c ON c.connector_id=ds.connector_id
                  JOIN catalog_relationship cr ON 1=1
                  JOIN catalog_table ct ON ct.catalog_table_id=cr.child_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                  JOIN catalog_relationship_column crc ON crc.relationship_id=cr.relationship_id
                  JOIN masking_rule pmr ON pmr.ruleset_version_id=j.ruleset_version_id
                       AND pmr.catalog_column_id=crc.parent_column_id AND pmr.enabled='Y'
                  JOIN masking_rule cmr ON cmr.ruleset_version_id=j.ruleset_version_id
                       AND cmr.catalog_column_id=crc.child_column_id AND cmr.enabled='Y'
                  JOIN algorithm_version pav ON pav.algorithm_version_id=pmr.algorithm_version_id
                  JOIN algorithm pa ON pa.algorithm_id=pav.algorithm_id
                  JOIN algorithm_version cav ON cav.algorithm_version_id=cmr.algorithm_version_id
                  JOIN algorithm ca ON ca.algorithm_id=cav.algorithm_id
                 WHERE j.job_id=? AND cs.data_source_id=j.data_source_id
                   AND pa.code='RELATIONSHIP_AWARE_TOKEN'
                   AND ca.code='RELATIONSHIP_AWARE_TOKEN'
                """;
        try (PreparedStatement statement = repository.prepareStatement(sql)) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) constraints.add(new RelationshipConstraint(
                        rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4)));
            }
        }
        return constraints;
    }

    private void deferOrDisableConstraints(List<RelationshipConstraint> constraints)
            throws SQLException {
        if (constraints.isEmpty()) return;
        String technology = constraints.get(0).technology();
        try (Statement statement = target.createStatement()) {
            if ("SQLSERVER".equals(technology)) {
                for (RelationshipConstraint constraint : constraints) statement.execute(
                        "ALTER TABLE " + qualified(technology,constraint.schema(),constraint.table())
                                + " NOCHECK CONSTRAINT "
                                + quote(technology,constraint.constraintName()));
            } else {
                String names = String.join(",",constraints.stream()
                        .map(item -> quote(technology,item.constraintName())).toList());
                try { statement.execute("SET CONSTRAINTS " + names + " DEFERRED"); }
                catch (SQLException error) {
                    throw new SQLException("RELATIONSHIP_AWARE_TOKEN requires DEFERRABLE foreign "
                            + "keys on " + technology + ": " + error.getMessage(),error);
                }
            }
        }
    }

    private void restoreConstraints(List<RelationshipConstraint> constraints)
            throws SQLException {
        if (constraints.isEmpty()) return;
        String technology = constraints.get(0).technology();
        try (Statement statement = target.createStatement()) {
            if ("SQLSERVER".equals(technology)) {
                for (RelationshipConstraint constraint : constraints) statement.execute(
                        "ALTER TABLE " + qualified(technology,constraint.schema(),constraint.table())
                                + " WITH CHECK CHECK CONSTRAINT "
                                + quote(technology,constraint.constraintName()));
            } else {
                String names = String.join(",",constraints.stream()
                        .map(item -> quote(technology,item.constraintName())).toList());
                statement.execute("SET CONSTRAINTS " + names + " IMMEDIATE");
            }
        }
    }

    private static String qualified(String technology,String schema,String table) {
        return quote(technology,schema) + "." + quote(technology,table);
    }

    private static String quote(String technology,String identifier) {
        if ("SQLSERVER".equals(technology)) return "[" + identifier.replace("]","]]" ) + "]";
        return "\"" + identifier.replace("\"","\"\"") + "\"";
    }

    private Map<Long,Integer> dependencyGroups(long jobId) throws SQLException {
        Map<Long,Integer> groups = new LinkedHashMap<>();
        try (PreparedStatement statement = repository.prepareStatement(
                "SELECT job_step_id,dependency_group FROM job_step WHERE job_id=? AND step_type='ANONYMIZE_TABLE'")) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) groups.put(rows.getLong(1), rows.getInt(2));
            }
        }
        return groups;
    }

    private long expectedRows(String sql, Operation operation) throws SQLException {
        int where = findTopLevelWhere(sql, operation.targetEnd());
        String countSql = "SELECT COUNT(*) FROM " + operation.table()
                + (where < 0 ? "" : " " + sql.substring(where));
        try (PreparedStatement statement = target.prepareStatement(countSql);
                ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private void saveExpectedRows(long stepId, long expected) throws SQLException {
        try (PreparedStatement delete = repository.prepareStatement(
                "DELETE FROM job_metric WHERE job_step_id=? AND metric_name='ROWS_EXPECTED'")) {
            delete.setLong(1, stepId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = repository.prepareStatement("""
                INSERT INTO job_metric(job_id,job_step_id,metric_name,metric_value,metric_unit)
                SELECT job_id,?,'ROWS_EXPECTED',?,'ROWS' FROM job_step WHERE job_step_id=?
                """)) {
            insert.setLong(1, stepId);
            insert.setLong(2, expected);
            insert.setLong(3, stepId);
            if (insert.executeUpdate() != 1) throw new SQLException("Execution step was not found");
        }
    }

    private static int findTopLevelWhere(String sql, int start) {
        int depth = 0;
        boolean quoted = false;
        for (int index = start; index <= sql.length() - 5; index++) {
            char current = sql.charAt(index);
            if (current == '\'') {
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') { index++; continue; }
                quoted = !quoted;
            }
            if (quoted) continue;
            if (current == '(') depth++;
            else if (current == ')') depth--;
            else if (depth == 0 && sql.regionMatches(true, index, "WHERE", 0, 5)
                    && (index == 0 || Character.isWhitespace(sql.charAt(index - 1)))
                    && (index + 5 == sql.length() || Character.isWhitespace(sql.charAt(index + 5)))) return index;
        }
        return -1;
    }

    private static void validate(ExecutableStep step) {
        String code = step.generatedCode().strip();
        if (!SqlChecksum.sha256(step.generatedCode()).equals(step.checksum().value())) {
            throw new IllegalStateException("SQL checksum mismatch at step " + step.stepNumber());
        }
        if (code.contains(";")) throw new IllegalStateException(
                "DIRECT mode accepts one generated statement per step without a semicolon");
        operation(code);
    }

    private static Operation operation(String sql) {
        Matcher update = UPDATE.matcher(sql);
        if (update.find()) return new Operation("UPDATE", update.group(1), update.end());
        Matcher delete = DELETE.matcher(sql);
        if (delete.matches()) return new Operation("DELETE", delete.group(1), delete.end(1));
        Matcher truncate = TRUNCATE.matcher(sql);
        if (truncate.matches()) return new Operation("TRUNCATE", truncate.group(1), truncate.end(1));
        throw new IllegalStateException(
                "DIRECT mode accepts generated UPDATE, DELETE or TRUNCATE statements");
    }

    private record Operation(String type, String table, int targetEnd) { }
    private record RelationshipConstraint(String technology,String constraintName,
            String schema,String table) { }
}
