package com.privonym.repository.oracle;

import com.privonym.application.port.out.TargetDataValidator;
import com.privonym.application.service.ValidationMetric;
import com.privonym.application.service.ValidationOutcome;
import com.privonym.domain.catalog.DataSourceId;
import com.privonym.domain.job.JobId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class OracleTargetDataValidator implements TargetDataValidator {
    private final Connection repository;
    private final Connection target;

    public OracleTargetDataValidator(Connection repository, Connection target) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public ValidationOutcome validate(DataSourceId dataSourceId, JobId jobId) {
        List<TableResult> tables = tableResults(dataSourceId, jobId);
        List<String> messages = new ArrayList<>();
        List<ValidationMetric> metrics = new ArrayList<>();
        long expectedTotal = 0;
        long anonymizedTotal = 0;
        List<String> mismatches = new ArrayList<>();
        for (TableResult table : tables) {
            expectedTotal += table.expected();
            anonymizedTotal += table.anonymized();
            String key = metricKey(table.schema(), table.table());
            metrics.add(new ValidationMetric("EXPECTED_" + key, table.expected()));
            metrics.add(new ValidationMetric("ANONYMIZED_" + key, table.anonymized()));
            metrics.add(new ValidationMetric("PROCESSED_"+key,table.anonymized()));
            long difference = Math.abs(table.anonymized() - table.expected());
            metrics.add(new ValidationMetric("DIFFERENCE_" + key, difference));
            String status = difference == 0 ? "PASSED" : "FAILED";
            messages.add("Execution counters " + table.schema() + "." + table.table() + ": expected=" + table.expected()
                    + ", reported processed=" + table.anonymized() + ", difference="
                    + (table.anonymized() - table.expected()) + ", status=" + status);
            if (difference != 0) mismatches.add(table.schema() + "." + table.table());
        }
        metrics.add(new ValidationMetric("ROWS_EXPECTED_TOTAL", expectedTotal));
        metrics.add(new ValidationMetric("ROWS_ANONYMIZED_TOTAL", anonymizedTotal));
        metrics.add(new ValidationMetric("ROWS_PROCESSED_TOTAL",anonymizedTotal));
        if (!mismatches.isEmpty()) throw new IllegalStateException(
                "Row-count validation failed for table(s): " + String.join(", ", mismatches));
        messages.add("Execution counter consistency passed for " + tables.size() + " table(s)");
        validateTargetPopulations(dataSourceId,jobId,tables,messages,metrics);
        validateReferentialIntegrity(dataSourceId, messages, metrics);
        messages.add("PRIVACY_NOT_ASSESSED: Population and relationship checks do not prove removal of sensitive data. No content detector was run.");
        metrics.add(new ValidationMetric("PRIVACY_ASSESSED",0));
        return new ValidationOutcome(messages, metrics);
    }

    private void validateTargetPopulations(DataSourceId source,JobId jobId,List<TableResult> tables,
            List<String> messages,List<ValidationMetric> metrics) {
        Map<String,Long> expected=new LinkedHashMap<>();
        String sql="""
                SELECT cs.schema_name,ct.table_name,m.metric_value FROM job_step s
                JOIN catalog_table ct ON ct.catalog_table_id=s.catalog_table_id
                JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                JOIN job_metric m ON m.job_step_id=s.job_step_id AND m.metric_name='TARGET_ROWS_EXPECTED'
                WHERE s.job_id=? AND cs.data_source_id=? AND s.step_type='ANONYMIZE_TABLE' AND s.status='SUCCEEDED'
                ORDER BY s.step_number
                """;
        try(PreparedStatement query=repository.prepareStatement(sql)) {
            query.setLong(1,jobId.value());query.setLong(2,source.value());
            try(ResultSet row=query.executeQuery()) {
                while(row.next())expected.put(row.getString(1)+"\0"+row.getString(2),row.getLong(3));
            }
            String technology=technology(source);
            for(TableResult table:tables) {
                long actual;
                try(PreparedStatement count=target.prepareStatement("SELECT "+(technology.equals("SQLSERVER")?"COUNT_BIG(*)":"COUNT(*)")+" FROM "+qualified(technology,table.schema(),table.table()));
                        ResultSet row=count.executeQuery()) {
                    if(!row.next())throw new SQLException("Missing target population result");actual=row.getLong(1);
                }
                String key=metricKey(table.schema(),table.table());
                metrics.add(new ValidationMetric("TARGET_ACTUAL_"+key,actual));
                Long before=expected.get(table.schema()+"\0"+table.table());
                metrics.add(new ValidationMetric("TARGET_CHECKED_"+key,before==null?0:1));
                if(before==null) {
                    messages.add("TARGET_COUNT_NOT_CHECKED: "+table.schema()+"."+table.table()+" actual rows="+actual
                            +"; no pre-execution baseline exists for this historical/partial execution.");
                    continue;
                }
                metrics.add(new ValidationMetric("TARGET_EXPECT_"+key,before));
                metrics.add(new ValidationMetric("TARGET_DIFF_"+key,Math.abs(actual-before)));
                if(actual!=before)throw new IllegalStateException("Independent target row-count validation failed for "
                        +table.schema()+"."+table.table()+": expected="+before+", actual="+actual);
                messages.add("Target population "+table.schema()+"."+table.table()+": expected="+before+", actual="+actual+", status=PASSED");
            }
        }catch(SQLException error){throw failure("independently count target rows",error);}
    }

    private void validateReferentialIntegrity(DataSourceId source,List<String> messages,
            List<ValidationMetric> metrics) {
        String technology = technology(source);
        Map<Long,RelationshipValidation> relationships = new LinkedHashMap<>();
        String sql = """
                SELECT cr.relationship_id,cr.constraint_name,
                       ps.schema_name parent_schema,pt.table_name parent_table,
                       cs.schema_name child_schema,ct.table_name child_table,
                       pc.column_name parent_column,cc.column_name child_column
                  FROM catalog_relationship cr
                  JOIN catalog_relationship_column crc ON crc.relationship_id=cr.relationship_id
                  JOIN catalog_column pc ON pc.catalog_column_id=crc.parent_column_id
                  JOIN catalog_table pt ON pt.catalog_table_id=pc.catalog_table_id
                  JOIN catalog_schema ps ON ps.catalog_schema_id=pt.catalog_schema_id
                  JOIN catalog_column cc ON cc.catalog_column_id=crc.child_column_id
                  JOIN catalog_table ct ON ct.catalog_table_id=cc.catalog_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                 WHERE cs.data_source_id=?
                 ORDER BY cr.relationship_id,crc.position_number
                """;
        try (PreparedStatement statement = repository.prepareStatement(sql)) {
            statement.setLong(1, source.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    RelationshipValidation relationship = relationships.computeIfAbsent(
                            rows.getLong("relationship_id"), ignored -> new RelationshipValidation(
                                    rowsUnchecked(rows,"constraint_name"),
                                    rowsUnchecked(rows,"parent_schema"),
                                    rowsUnchecked(rows,"parent_table"),
                                    rowsUnchecked(rows,"child_schema"),
                                    rowsUnchecked(rows,"child_table"),new ArrayList<>()));
                    relationship.columns().add(new RelationshipColumn(
                            rows.getString("parent_column"),rows.getString("child_column")));
                }
            }
            List<String> broken = new ArrayList<>();
            for (RelationshipValidation relationship : relationships.values()) {
                long orphans = orphanCount(technology, relationship);
                String key = metricKey(relationship.childSchema(), relationship.constraintName());
                metrics.add(new ValidationMetric("RI_ORPHANS_" + key, orphans));
                messages.add("Foreign key " + relationship.constraintName() + ": orphan rows="
                        + orphans + ", status=" + (orphans == 0 ? "PASSED" : "FAILED"));
                if (orphans != 0) broken.add(relationship.constraintName());
            }
            if (!relationships.isEmpty()) messages.add("Referential-integrity validation passed for "
                    + relationships.size() + " foreign key(s)");
            if (!broken.isEmpty()) throw new IllegalStateException(
                    "Referential-integrity validation failed for: " + String.join(", ", broken));
        } catch (SQLException error) {
            throw failure("validate referential integrity", error);
        }
    }

    private String technology(DataSourceId source) {
        try (PreparedStatement statement = repository.prepareStatement("""
                SELECT c.technology_code FROM data_source d
                JOIN connector c ON c.connector_id=d.connector_id WHERE d.data_source_id=?
                """)) {
            statement.setLong(1, source.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Data source was not found");
                return row.getString(1).toUpperCase(Locale.ROOT);
            }
        } catch (SQLException error) {
            throw failure("load target technology", error);
        }
    }

    private long orphanCount(String technology,RelationshipValidation relationship)
            throws SQLException {
        String childTable = qualified(technology,relationship.childSchema(),relationship.childTable());
        String parentTable = qualified(technology,relationship.parentSchema(),relationship.parentTable());
        List<String> joins = new ArrayList<>();
        List<String> nonNull = new ArrayList<>();
        for (RelationshipColumn column : relationship.columns()) {
            joins.add("c." + quote(technology,column.child()) + " = p."
                    + quote(technology,column.parent()));
            nonNull.add("c." + quote(technology,column.child()) + " IS NOT NULL");
        }
        String query = "SELECT COUNT(*) FROM " + childTable + " c LEFT JOIN " + parentTable
                + " p ON " + String.join(" AND ",joins) + " WHERE "
                + String.join(" AND ",nonNull) + " AND p."
                + quote(technology,relationship.columns().get(0).parent()) + " IS NULL";
        try (PreparedStatement statement = target.prepareStatement(query);
                ResultSet row = statement.executeQuery()) {
            row.next(); return row.getLong(1);
        }
    }

    private static String qualified(String technology,String schema,String table) {
        return quote(technology,schema) + "." + quote(technology,table);
    }

    private static String quote(String technology,String identifier) {
        if ("SQLSERVER".equals(technology)) return "[" + identifier.replace("]","]]" ) + "]";
        return "\"" + identifier.replace("\"","\"\"") + "\"";
    }

    private static String rowsUnchecked(ResultSet rows,String column) {
        try { return rows.getString(column); }
        catch (SQLException error) { throw new IllegalStateException(error); }
    }

    private List<TableResult> tableResults(DataSourceId source, JobId jobId) {
        String sql = """
                SELECT cs.schema_name, ct.table_name,
                       SUM(NVL(m.metric_value, -1)) expected_rows,
                       SUM(a.rows_processed) anonymized_rows
                  FROM anonymization_job j
                  JOIN job_step s ON s.job_id=j.job_id
                  JOIN catalog_table ct ON ct.catalog_table_id=s.catalog_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                  JOIN job_step_attempt a ON a.job_step_id=s.job_step_id
                  LEFT JOIN job_metric m ON m.job_step_id=s.job_step_id
                       AND m.metric_name='ROWS_EXPECTED'
                 WHERE j.job_id=? AND j.data_source_id=?
                   AND s.step_type='ANONYMIZE_TABLE' AND s.status='SUCCEEDED'
                   AND a.status='SUCCEEDED'
                   AND a.attempt_number=(SELECT MAX(x.attempt_number)
                         FROM job_step_attempt x WHERE x.job_step_id=s.job_step_id)
                 GROUP BY cs.schema_name,ct.table_name
                 ORDER BY cs.schema_name,ct.table_name
                """;
        List<TableResult> results = new ArrayList<>();
        try (PreparedStatement statement = repository.prepareStatement(sql)) {
            statement.setLong(1, jobId.value());
            statement.setLong(2, source.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long expected = rows.getLong("expected_rows");
                    if (expected < 0) throw new IllegalStateException(
                            "Expected row count was not captured for " + rows.getString("schema_name")
                                    + "." + rows.getString("table_name"));
                    results.add(new TableResult(rows.getString("schema_name"),
                            rows.getString("table_name"), expected,
                            rows.getLong("anonymized_rows")));
                }
            }
        } catch (SQLException error) {
            throw failure("read per-table execution metrics", error);
        }
        if (results.isEmpty()) throw new IllegalStateException("No successful table execution was found");
        return List.copyOf(results);
    }

    private static String metricKey(String schema, String table) {
        String value = (schema + "_" + table).toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        return value.substring(0, Math.min(value.length(), 85));
    }

    private static IllegalStateException failure(String operation, SQLException error) {
        return new IllegalStateException("Unable to " + operation + ": "
                + error.getMessage(), error);
    }

    private record TableResult(String schema, String table, long expected, long anonymized) { }
    private record RelationshipColumn(String parent,String child) { }
    private record RelationshipValidation(String constraintName,String parentSchema,
            String parentTable,String childSchema,String childTable,
            List<RelationshipColumn> columns) { }
}
