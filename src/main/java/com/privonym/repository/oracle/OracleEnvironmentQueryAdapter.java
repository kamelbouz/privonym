package com.privonym.repository.oracle;

import com.privonym.application.port.out.EnvironmentQueryRepository;
import com.privonym.application.port.out.InventoryQueryRepository;
import com.privonym.application.service.DataSourceInventory;
import com.privonym.application.service.DataSourceSummary;
import com.privonym.application.service.EnvironmentDetails;
import com.privonym.application.service.EnvironmentSummary;
import com.privonym.application.service.InventoryColumn;
import com.privonym.application.service.InventorySchema;
import com.privonym.application.service.InventoryTable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class OracleEnvironmentQueryAdapter implements EnvironmentQueryRepository,
        InventoryQueryRepository, AutoCloseable {
    private static final String SUMMARY_COLUMNS = """
            e.environment_id, e.code, e.name, e.status, e.owner_name,
            (SELECT COUNT(*) FROM data_source d WHERE d.environment_id = e.environment_id) source_count,
            (SELECT COUNT(*) FROM anonymization_job j JOIN data_source d
               ON d.data_source_id = j.data_source_id
              WHERE d.environment_id = e.environment_id) job_count
            """;

    private final String jdbcUrl;
    private final String username;
    private final char[] password;

    public OracleEnvironmentQueryAdapter(String jdbcUrl, String username, char[] password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password").clone();
    }

    @Override
    public List<EnvironmentSummary> list() {
        String sql = "SELECT " + SUMMARY_COLUMNS + " FROM environment e ORDER BY e.name";
        List<EnvironmentSummary> environments = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                environments.add(summary(row));
            }
            return environments;
        } catch (SQLException error) {
            throw failure("list environments", error);
        }
    }

    @Override
    public EnvironmentDetails get(String code) {
        String environmentSql = "SELECT " + SUMMARY_COLUMNS
                + ", e.description FROM environment e WHERE e.code = ?";
        String sourceSql = """
                SELECT d.data_source_id, d.name, d.enabled, c.technology_code, c.status connector_status,
                       c.host_name, c.port, c.service_name, c.username_value,
                       (SELECT COUNT(*) FROM catalog_schema s WHERE s.data_source_id = d.data_source_id) schema_count,
                       (SELECT COUNT(*) FROM catalog_table t JOIN catalog_schema s
                          ON s.catalog_schema_id = t.catalog_schema_id
                         WHERE s.data_source_id = d.data_source_id) table_count,
                       (SELECT COUNT(*) FROM anonymization_job j
                         WHERE j.data_source_id = d.data_source_id) job_count
                  FROM data_source d JOIN connector c ON c.connector_id = d.connector_id
                 WHERE d.environment_id = ? ORDER BY d.name
                """;
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(environmentSql)) {
            statement.setString(1, code);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException("Environment was not found: " + code);
                }
                EnvironmentSummary environment = summary(row);
                return new EnvironmentDetails(environment, row.getString("description"),
                        loadSources(connection, sourceSql, environment.id()));
            }
        } catch (SQLException error) {
            throw failure("load environment", error);
        }
    }

    @Override
    public EnvironmentSummary create(String code, String name, String description) {
        String sql = """
                INSERT INTO environment(code,name,description,status,owner_name)
                VALUES(?,?,?,'ACTIVE',?)
                """;
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, username, new String(password))) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, code);
                statement.setString(2, name);
                statement.setString(3, description.isBlank() ? null : description);
                statement.setString(4, username);
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            if (error.getErrorCode() == 1) {
                throw new IllegalArgumentException("Environment code already exists: " + code);
            }
            throw failure("create environment", error);
        }
        return get(code).environment();
    }

    @Override
    public DataSourceInventory getInventory(long dataSourceId) {
        String sourceSql = """
                SELECT ds.name,c.technology_code
                  FROM data_source ds JOIN connector c ON c.connector_id=ds.connector_id
                 WHERE ds.data_source_id=?
                """;
        String inventorySql = """
                SELECT s.schema_name,
                       TO_CHAR(s.synchronized_at, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF3TZH:TZM') schema_sync,
                       t.catalog_table_id, t.table_name, t.object_type, t.estimated_row_count, t.enabled,
                       TO_CHAR(t.synchronized_at, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF3TZH:TZM') table_sync,
                       c.column_name, c.ordinal_position, c.native_data_type, c.normalized_data_type,
                       c.nullable, c.primary_key_member, c.unique_member
                  FROM catalog_schema s
                  LEFT JOIN catalog_table t ON t.catalog_schema_id = s.catalog_schema_id
                  LEFT JOIN catalog_column c ON c.catalog_table_id = t.catalog_table_id
                 WHERE s.data_source_id = ?
                 ORDER BY s.schema_name, t.table_name, c.ordinal_position
                """;
        try (Connection connection = open()) {
            SourceIdentity source = loadSourceIdentity(connection, sourceSql, dataSourceId);
            Map<String, SchemaBuilder> schemas = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(inventorySql)) {
                statement.setLong(1, dataSourceId);
                try (ResultSet row = statement.executeQuery()) {
                    while (row.next()) {
                        SchemaBuilder schema = schemas.computeIfAbsent(row.getString("schema_name"),
                                ignored -> new SchemaBuilder(rowString(row, "schema_name"),
                                        rowString(row, "schema_sync")));
                        long tableId = row.getLong("catalog_table_id");
                        if (row.wasNull()) {
                            continue;
                        }
                        TableBuilder table = schema.tables.computeIfAbsent(tableId,
                                ignored -> new TableBuilder(tableId, rowString(row, "table_name"),
                                        rowString(row, "object_type"), nullableLong(row, "estimated_row_count"),
                                        "Y".equals(rowString(row, "enabled")), rowString(row, "table_sync")));
                        String columnName = row.getString("column_name");
                        if (columnName != null) {
                            table.columns.add(new InventoryColumn(columnName, row.getInt("ordinal_position"),
                                    rowString(row, "native_data_type"), rowString(row, "normalized_data_type"),
                                    "Y".equals(rowString(row, "nullable")),
                                    "Y".equals(rowString(row, "primary_key_member")),
                                    "Y".equals(rowString(row, "unique_member"))));
                        }
                    }
                }
            }
            return new DataSourceInventory(dataSourceId, source.name(), source.technology(),
                    schemas.values().stream().map(SchemaBuilder::freeze).toList());
        } catch (SQLException error) {
            throw failure("load data source inventory", error);
        }
    }

    private static SourceIdentity loadSourceIdentity(Connection connection, String sql,
            long dataSourceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, dataSourceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException("Data source was not found: " + dataSourceId);
                }
                return new SourceIdentity(row.getString(1), row.getString(2));
            }
        }
    }

    private record SourceIdentity(String name, String technology) { }

    private static String rowString(ResultSet row, String column) {
        try {
            String value = row.getString(column);
            return value == null ? "" : value;
        } catch (SQLException error) {
            throw failure("read inventory column", error);
        }
    }

    private static Long nullableLong(ResultSet row, String column) {
        try {
            long value = row.getLong(column);
            return row.wasNull() ? null : value;
        } catch (SQLException error) {
            throw failure("read inventory number", error);
        }
    }

    private static List<DataSourceSummary> loadSources(Connection connection, String sql, long environmentId)
            throws SQLException {
        List<DataSourceSummary> sources = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, environmentId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    sources.add(new DataSourceSummary(row.getLong("data_source_id"), row.getString("name"),
                            "Y".equals(row.getString("enabled")), row.getString("technology_code"),
                            row.getString("connector_status"), row.getString("host_name"), row.getInt("port"),
                            row.getString("service_name"), row.getString("username_value"),
                            row.getInt("schema_count"), row.getInt("table_count"), row.getInt("job_count")));
                }
            }
        }
        return sources;
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, username, new String(password));
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        return connection;
    }

    private static EnvironmentSummary summary(ResultSet row) throws SQLException {
        return new EnvironmentSummary(row.getLong("environment_id"), row.getString("code"),
                row.getString("name"), row.getString("status"), row.getString("owner_name"),
                row.getInt("source_count"), row.getInt("job_count"));
    }

    private static IllegalStateException failure(String action, SQLException error) {
        return new IllegalStateException("Unable to " + action + ": " + error.getMessage(), error);
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }

    private static final class SchemaBuilder {
        private final String name;
        private final String synchronizedAt;
        private final Map<Long, TableBuilder> tables = new LinkedHashMap<>();

        private SchemaBuilder(String name, String synchronizedAt) {
            this.name = name;
            this.synchronizedAt = synchronizedAt;
        }

        private InventorySchema freeze() {
            return new InventorySchema(name, synchronizedAt,
                    tables.values().stream().map(TableBuilder::freeze).toList());
        }
    }

    private static final class TableBuilder {
        private final long id;
        private final String name;
        private final String objectType;
        private final Long estimatedRows;
        private final boolean enabled;
        private final String synchronizedAt;
        private final List<InventoryColumn> columns = new ArrayList<>();

        private TableBuilder(long id, String name, String objectType, Long estimatedRows,
                boolean enabled, String synchronizedAt) {
            this.id = id;
            this.name = name;
            this.objectType = objectType;
            this.estimatedRows = estimatedRows;
            this.enabled = enabled;
            this.synchronizedAt = synchronizedAt;
        }

        private InventoryTable freeze() {
            return new InventoryTable(id, name, objectType, estimatedRows,
                    enabled, synchronizedAt, columns);
        }
    }
}
