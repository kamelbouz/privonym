package com.privonym.repository.postgresql;

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

/** PostgreSQL target catalogue and privilege preflight; repository remains Oracle. */
public final class PostgresqlTargetPreflightAdapter implements TargetPreflightAdapter {
    private final Connection repository;
    private final Connection target;

    public PostgresqlTargetPreflightAdapter(Connection repository, Connection target) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public List<String> verify(DataSourceId dataSourceId, JobId jobId) {
        List<String> messages = new ArrayList<>();
        messages.add(identity());
        String columns = """
                SELECT DISTINCT cs.schema_name,ct.table_name,cc.column_name
                  FROM anonymization_job j
                  JOIN masking_rule mr ON mr.ruleset_version_id=j.ruleset_version_id AND mr.enabled='Y'
                  JOIN catalog_column cc ON cc.catalog_column_id=mr.catalog_column_id
                  JOIN catalog_table ct ON ct.catalog_table_id=cc.catalog_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                 WHERE j.job_id=? AND j.data_source_id=?
                """;
        int checked = 0;
        try (PreparedStatement statement = repository.prepareStatement(columns)) {
            statement.setLong(1, jobId.value()); statement.setLong(2, dataSourceId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    requireColumn(rows.getString(1), rows.getString(2), rows.getString(3));
                    requirePrivilege(rows.getString(1), rows.getString(2), "UPDATE");
                    checked++;
                }
            }
        } catch (SQLException error) { throw failure("read target catalogue", error); }
        String tables = """
                SELECT DISTINCT cs.schema_name,ct.table_name,a.code
                  FROM anonymization_job j
                  JOIN table_masking_rule tr ON tr.ruleset_version_id=j.ruleset_version_id AND tr.enabled='Y'
                  JOIN catalog_table ct ON ct.catalog_table_id=tr.catalog_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
                  JOIN algorithm_version av ON av.algorithm_version_id=tr.algorithm_version_id
                  JOIN algorithm a ON a.algorithm_id=av.algorithm_id
                 WHERE j.job_id=? AND j.data_source_id=?
                """;
        try (PreparedStatement statement = repository.prepareStatement(tables)) {
            statement.setLong(1, jobId.value()); statement.setLong(2, dataSourceId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    requireTable(rows.getString(1), rows.getString(2));
                    requirePrivilege(rows.getString(1), rows.getString(2),
                            "TRUNCATE_TABLE".equals(rows.getString(3)) ? "TRUNCATE" : "DELETE");
                    checked++;
                }
            }
        } catch (SQLException error) { throw failure("read structural targets", error); }
        verifyRequiredFunctions(jobId);
        if (checked == 0) throw new IllegalStateException("No target rule is defined for this job");
        messages.add("PostgreSQL target catalogue: " + checked + " target(s) verified");
        return List.copyOf(messages);
    }

    private void verifyRequiredFunctions(JobId jobId) {
        String sql = """
                SELECT COUNT(*) FROM anonymization_job j
                  JOIN masking_rule mr ON mr.ruleset_version_id=j.ruleset_version_id AND mr.enabled='Y'
                  JOIN algorithm_version av ON av.algorithm_version_id=mr.algorithm_version_id
                  JOIN algorithm a ON a.algorithm_id=av.algorithm_id
                 WHERE j.job_id=? AND a.code='IBAN_GENERATOR'
                """;
        try (PreparedStatement statement = repository.prepareStatement(sql)) {
            statement.setLong(1, jobId.value());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                if (row.getInt(1) > 0) requireIbanFunction();
            }
        } catch (SQLException error) { throw failure("read PostgreSQL function requirements", error); }
    }

    private void requireIbanFunction() {
        try (PreparedStatement statement = target.prepareStatement(
                "SELECT to_regprocedure('privonym_iban_generate(text,text)')");
                ResultSet row = statement.executeQuery()) {
            row.next();
            if (row.getObject(1) == null) throw new IllegalStateException(
                    "PostgreSQL function privonym_iban_generate(text,text) is required by algorithm IBAN_GENERATOR");
        } catch (SQLException error) { throw failure("verify PostgreSQL IBAN function", error); }
    }

    private String identity() {
        try (PreparedStatement statement = target.prepareStatement(
                "SELECT current_user,current_database()"); ResultSet row = statement.executeQuery()) {
            row.next(); return "Target connection established: " + row.getString(1) + "@" + row.getString(2);
        } catch (SQLException error) { throw failure("identify PostgreSQL target", error); }
    }

    private void requireColumn(String schema, String table, String column) {
        try (PreparedStatement statement = target.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema=? AND table_name=? AND column_name=?
                """)) {
            statement.setString(1, schema); statement.setString(2, table); statement.setString(3, column);
            try (ResultSet row = statement.executeQuery()) { row.next(); if (row.getInt(1) != 1)
                throw new IllegalStateException("Target column was not found: " + schema + "." + table + "." + column); }
        } catch (SQLException error) { throw failure("verify PostgreSQL column", error); }
    }

    private void requireTable(String schema, String table) {
        try (PreparedStatement statement = target.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema=? AND table_name=? AND table_type='BASE TABLE'
                """)) {
            statement.setString(1, schema); statement.setString(2, table);
            try (ResultSet row = statement.executeQuery()) { row.next(); if (row.getInt(1) != 1)
                throw new IllegalStateException("Target table was not found: " + schema + "." + table); }
        } catch (SQLException error) { throw failure("verify PostgreSQL table", error); }
    }

    private void requirePrivilege(String schema, String table, String privilege) {
        try (PreparedStatement statement = target.prepareStatement(
                "SELECT has_table_privilege(current_user,quote_ident(?)||'.'||quote_ident(?),?)")) {
            statement.setString(1, schema); statement.setString(2, table); statement.setString(3, privilege);
            try (ResultSet row = statement.executeQuery()) { row.next(); if (!row.getBoolean(1))
                throw new IllegalStateException("Target user has no " + privilege + " privilege on " + schema + "." + table); }
        } catch (SQLException error) { throw failure("verify PostgreSQL privilege", error); }
    }

    private static IllegalStateException failure(String operation, SQLException error) {
        return new IllegalStateException("Unable to " + operation + ": " + error.getMessage(), error);
    }
}
