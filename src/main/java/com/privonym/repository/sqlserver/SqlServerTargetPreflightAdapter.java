package com.privonym.repository.sqlserver;

import com.privonym.application.port.out.TargetPreflightAdapter;
import com.privonym.domain.catalog.DataSourceId;
import com.privonym.domain.job.JobId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Microsoft SQL Server target catalogue and privilege preflight; repository remains Oracle. */
public final class SqlServerTargetPreflightAdapter implements TargetPreflightAdapter {
    private final Connection repository;
    private final Connection target;

    public SqlServerTargetPreflightAdapter(Connection repository, Connection target) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public List<String> verify(DataSourceId dataSourceId, JobId jobId) {
        List<String> messages = new ArrayList<>();
        messages.add(identity());
        int checked = verifyColumns(dataSourceId, jobId);
        checked += verifyTables(dataSourceId, jobId);
        verifyRequiredFunctions(jobId);
        if (checked == 0) throw new IllegalStateException("No target rule is defined for this job");
        messages.add("SQL Server target catalogue: " + checked + " target(s) verified");
        return List.copyOf(messages);
    }

    private int verifyColumns(DataSourceId dataSourceId, JobId jobId) {
        String sql = """
                SELECT DISTINCT cs.schema_name,ct.table_name,cc.column_name
                  FROM anonymization_job j
                  JOIN masking_rule mr ON mr.ruleset_version_id=j.ruleset_version_id AND mr.enabled='Y'
                  JOIN catalog_column cc ON cc.catalog_column_id=mr.catalog_column_id
                  JOIN catalog_table ct ON ct.catalog_table_id=cc.catalog_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                 WHERE j.job_id=? AND j.data_source_id=?
                """;
        int checked = 0;
        try (PreparedStatement statement = repository.prepareStatement(sql)) {
            statement.setLong(1, jobId.value()); statement.setLong(2, dataSourceId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    requireColumn(rows.getString(1), rows.getString(2), rows.getString(3));
                    requirePrivilege(rows.getString(1), rows.getString(2), "UPDATE");
                    checked++;
                }
            }
            return checked;
        } catch (SQLException error) { throw failure("read target catalogue", error); }
    }

    private int verifyTables(DataSourceId dataSourceId, JobId jobId) {
        String sql = """
                SELECT DISTINCT cs.schema_name,ct.table_name,a.code
                  FROM anonymization_job j
                  JOIN table_masking_rule tr ON tr.ruleset_version_id=j.ruleset_version_id AND tr.enabled='Y'
                  JOIN catalog_table ct ON ct.catalog_table_id=tr.catalog_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                  JOIN algorithm_version av ON av.algorithm_version_id=tr.algorithm_version_id
                  JOIN algorithm a ON a.algorithm_id=av.algorithm_id
                 WHERE j.job_id=? AND j.data_source_id=?
                """;
        int checked = 0;
        try (PreparedStatement statement = repository.prepareStatement(sql)) {
            statement.setLong(1, jobId.value()); statement.setLong(2, dataSourceId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    requireTable(rows.getString(1), rows.getString(2));
                    requirePrivilege(rows.getString(1), rows.getString(2),
                            "TRUNCATE_TABLE".equals(rows.getString(3)) ? "ALTER" : "DELETE");
                    checked++;
                }
            }
            return checked;
        } catch (SQLException error) { throw failure("read structural targets", error); }
    }

    private String identity() {
        try (PreparedStatement statement = target.prepareStatement("SELECT SUSER_SNAME(),DB_NAME()");
             ResultSet row = statement.executeQuery()) {
            row.next(); return "Target connection established: " + row.getString(1) + "@" + row.getString(2);
        } catch (SQLException error) { throw failure("identify SQL Server target", error); }
    }

    private void requireColumn(String schema, String table, String column) {
        try (PreparedStatement statement = target.prepareStatement("""
                SELECT COUNT(*) FROM sys.schemas s JOIN sys.tables t ON t.schema_id=s.schema_id
                  JOIN sys.columns c ON c.object_id=t.object_id
                 WHERE s.name=? AND t.name=? AND c.name=?
                """)) {
            statement.setString(1, schema); statement.setString(2, table); statement.setString(3, column);
            try (ResultSet row = statement.executeQuery()) { row.next(); if (row.getInt(1) != 1)
                throw new IllegalStateException("Target column was not found: " + schema + "." + table + "." + column); }
        } catch (SQLException error) { throw failure("verify SQL Server column", error); }
    }

    private void requireTable(String schema, String table) {
        try (PreparedStatement statement = target.prepareStatement("""
                SELECT COUNT(*) FROM sys.schemas s JOIN sys.tables t ON t.schema_id=s.schema_id
                 WHERE s.name=? AND t.name=?
                """)) {
            statement.setString(1, schema); statement.setString(2, table);
            try (ResultSet row = statement.executeQuery()) { row.next(); if (row.getInt(1) != 1)
                throw new IllegalStateException("Target table was not found: " + schema + "." + table); }
        } catch (SQLException error) { throw failure("verify SQL Server table", error); }
    }

    private void requirePrivilege(String schema, String table, String privilege) {
        try (PreparedStatement statement = target.prepareStatement(
                "SELECT HAS_PERMS_BY_NAME(QUOTENAME(?) + N'.' + QUOTENAME(?),'OBJECT',?)")) {
            statement.setString(1, schema); statement.setString(2, table); statement.setString(3, privilege);
            try (ResultSet row = statement.executeQuery()) { row.next(); if (row.getInt(1) != 1)
                throw new IllegalStateException("Target user has no " + privilege + " privilege on " + schema + "." + table); }
        } catch (SQLException error) { throw failure("verify SQL Server privilege", error); }
    }

    private void verifyRequiredFunctions(JobId jobId) {
        String sql = """
                SELECT DISTINCT a.code FROM anonymization_job j
                  JOIN masking_rule mr ON mr.ruleset_version_id=j.ruleset_version_id AND mr.enabled='Y'
                  JOIN algorithm_version av ON av.algorithm_version_id=mr.algorithm_version_id
                  JOIN algorithm a ON a.algorithm_id=av.algorithm_id
                 WHERE j.job_id=? AND a.code IN('HMAC_SHA256','RELATIONSHIP_AWARE_TOKEN','REGEX_MASK','IBAN_GENERATOR')
                """;
        try (PreparedStatement statement = repository.prepareStatement(sql)) {
            statement.setLong(1, jobId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String code = rows.getString(1);
                    String function = switch (code) {
                        case "HMAC_SHA256", "RELATIONSHIP_AWARE_TOKEN" ->
                                "dbo.PRIVONYM_HMAC_SHA256";
                        case "IBAN_GENERATOR" -> "dbo.PRIVONYM_IBAN_GENERATE";
                        default -> "dbo.PRIVONYM_REGEX_REPLACE";
                    };
                    requireFunction(function, code);
                }
            }
        } catch (SQLException error) { throw failure("read SQL Server function requirements", error); }
    }

    private void requireFunction(String function, String algorithm) {
        try (PreparedStatement statement = target.prepareStatement("SELECT OBJECT_ID(?)")) {
            statement.setString(1, function);
            try (ResultSet row = statement.executeQuery()) { row.next(); if (row.getObject(1) == null)
                throw new IllegalStateException("SQL Server function " + function
                        + " is required by algorithm " + algorithm); }
        } catch (SQLException error) { throw failure("verify SQL Server function " + function, error); }
    }

    private static IllegalStateException failure(String operation, SQLException error) {
        return new IllegalStateException("Unable to " + operation + ": " + error.getMessage(), error);
    }
}
