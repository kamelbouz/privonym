package com.privonym.repository.oracle;

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

public final class OracleTargetPreflightAdapter implements TargetPreflightAdapter {
    private final Connection repository;
    private final Connection target;

    public OracleTargetPreflightAdapter(Connection repository, Connection target) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public List<String> verify(DataSourceId dataSourceId, JobId jobId) {
        List<String> messages = new ArrayList<>();
        String sessionUser = targetIdentity(messages);
        String sql = """
                SELECT DISTINCT cs.schema_name, ct.table_name, cc.column_name
                  FROM anonymization_job j
                  JOIN masking_rule mr ON mr.ruleset_version_id = j.ruleset_version_id
                                     AND mr.enabled = 'Y'
                  JOIN catalog_column cc ON cc.catalog_column_id = mr.catalog_column_id
                  JOIN catalog_table ct ON ct.catalog_table_id = cc.catalog_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id = ct.catalog_schema_id
                 WHERE j.job_id = ? AND j.data_source_id = ?
                 ORDER BY cs.schema_name, ct.table_name, cc.column_name
                """;
        int checkedColumns = 0;
        try (PreparedStatement statement = repository.prepareStatement(sql)) {
            statement.setLong(1, jobId.value());
            statement.setLong(2, dataSourceId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String owner = rows.getString("schema_name");
                    String table = rows.getString("table_name");
                    String column = rows.getString("column_name");
                    requireTargetColumn(owner, table, column);
                    requireUpdatePrivilege(sessionUser, owner, table);
                    checkedColumns++;
                }
            }
        } catch (SQLException error) {
            throw failure("read target catalogue", error);
        }
        String tableSql = """
                SELECT DISTINCT cs.schema_name, ct.table_name, a.code algorithm_code
                  FROM anonymization_job j
                  JOIN table_masking_rule tr ON tr.ruleset_version_id = j.ruleset_version_id
                                              AND tr.enabled = 'Y'
                  JOIN catalog_table ct ON ct.catalog_table_id = tr.catalog_table_id
                  JOIN catalog_schema cs ON cs.catalog_schema_id = ct.catalog_schema_id
                  JOIN algorithm_version av ON av.algorithm_version_id = tr.algorithm_version_id
                  JOIN algorithm a ON a.algorithm_id = av.algorithm_id
                 WHERE j.job_id = ? AND j.data_source_id = ?
                 ORDER BY cs.schema_name, ct.table_name, a.code
                """;
        int checkedTables = 0;
        try (PreparedStatement statement = repository.prepareStatement(tableSql)) {
            statement.setLong(1, jobId.value());
            statement.setLong(2, dataSourceId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String owner = rows.getString("schema_name");
                    String table = rows.getString("table_name");
                    String algorithm = rows.getString("algorithm_code");
                    requireTargetTable(owner, table);
                    if ("DELETE_TABLE_ROWS".equals(algorithm)) {
                        requireTablePrivilege(sessionUser, owner, table, "DELETE");
                    } else if ("TRUNCATE_TABLE".equals(algorithm)) {
                        requireTruncatePrivilege(sessionUser, owner, table);
                    } else {
                        throw new IllegalStateException(
                                "Unsupported structural algorithm: " + algorithm);
                    }
                    checkedTables++;
                }
            }
        } catch (SQLException error) {
            throw failure("read structural table targets", error);
        }
        verifyRequiredFunctions(jobId);
        if (checkedColumns + checkedTables == 0) {
            throw new IllegalStateException("No target rule is defined for this job");
        }
        messages.add("Target catalogue: " + checkedColumns + " masking column(s) and "
                + checkedTables + " structural table action(s) verified");
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
        } catch (SQLException error) { throw failure("read Oracle function requirements", error); }
    }

    private void requireIbanFunction() {
        try (PreparedStatement statement = target.prepareStatement("""
                SELECT COUNT(*) FROM user_objects
                 WHERE object_name='PRIVONYM_IBAN_GENERATE'
                   AND object_type='FUNCTION' AND status='VALID'
                """); ResultSet row = statement.executeQuery()) {
            row.next();
            if (row.getInt(1) != 1) throw new IllegalStateException(
                    "Oracle function PRIVONYM_IBAN_GENERATE is required by algorithm IBAN_GENERATOR");
        } catch (SQLException error) { throw failure("verify Oracle IBAN function", error); }
    }

    private String targetIdentity(List<String> messages) {
        String sql = """
                SELECT SYS_CONTEXT('USERENV','SESSION_USER') session_user,
                       SYS_CONTEXT('USERENV','DB_NAME') database_name
                  FROM dual
                """;
        try (PreparedStatement statement = target.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            row.next();
            String user = row.getString("session_user");
            messages.add("Target connection established: " + user + "@"
                    + row.getString("database_name"));
            return user;
        } catch (SQLException error) {
            throw failure("identify target connection", error);
        }
    }

    private void requireTargetColumn(String owner, String table, String column) {
        String sql = """
                SELECT COUNT(*) FROM all_tab_columns
                 WHERE owner = ? AND table_name = ? AND column_name = ?
                """;
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                if (row.getInt(1) != 1) {
                    throw new IllegalStateException(
                            "Target column was not found: " + owner + "." + table + "." + column);
                }
            }
        } catch (SQLException error) {
            throw failure("verify target column", error);
        }
    }

    private void requireTargetTable(String owner, String table) {
        try (PreparedStatement statement = target.prepareStatement(
                "SELECT COUNT(*) FROM all_tables WHERE owner = ? AND table_name = ?")) {
            statement.setString(1, owner);
            statement.setString(2, table);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                if (row.getInt(1) != 1) throw new IllegalStateException(
                        "Target table was not found: " + owner + "." + table);
            }
        } catch (SQLException error) {
            throw failure("verify target table", error);
        }
    }

    private void requireUpdatePrivilege(String sessionUser, String owner, String table) {
        requireTablePrivilege(sessionUser, owner, table, "UPDATE");
    }

    private void requireTablePrivilege(String sessionUser, String owner, String table,
            String privilege) {
        if (sessionUser.equalsIgnoreCase(owner) || hasSystemPrivilege(privilege + " ANY TABLE")
                || hasDirectGrant(sessionUser, owner, table, privilege)) return;
        throw new IllegalStateException("Target user " + sessionUser
                + " has no verified " + privilege + " privilege on " + owner + "." + table);
    }

    private void requireTruncatePrivilege(String sessionUser, String owner, String table) {
        if (sessionUser.equalsIgnoreCase(owner) || hasSystemPrivilege("DROP ANY TABLE")) return;
        throw new IllegalStateException("Target user " + sessionUser
                + " cannot truncate " + owner + "." + table
                + "; ownership or DROP ANY TABLE is required");
    }

    private boolean hasSystemPrivilege(String privilege) {
        String sql = "SELECT COUNT(*) FROM session_privs WHERE privilege = ?";
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            statement.setString(1, privilege);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1) > 0;
            }
        } catch (SQLException error) {
            throw failure("verify system privilege", error);
        }
    }

    private boolean hasDirectGrant(String user, String owner, String table, String privilege) {
        String sql = """
                SELECT COUNT(*) FROM all_tab_privs
                 WHERE grantee = ? AND owner = ? AND table_name = ?
                   AND privilege = ?
                """;
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            statement.setString(1, user);
            statement.setString(2, owner);
            statement.setString(3, table);
            statement.setString(4, privilege);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1) > 0;
            }
        } catch (SQLException error) {
            throw failure("verify object privilege", error);
        }
    }

    private static IllegalStateException failure(String operation, SQLException error) {
        return new IllegalStateException("Unable to " + operation + ": " + error.getMessage(), error);
    }
}
