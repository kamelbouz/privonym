package com.privonym.repository.oracle;

import com.privonym.application.port.out.MaskingInventoryQueryRepository;
import com.privonym.application.service.MaskingInventoryDetails;
import com.privonym.application.service.MaskingInventoryMapping;
import com.privonym.application.service.MaskingInventorySummary;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Read adapter for versioned masking inventories and Transformer review mappings. */
public final class OracleMaskingInventoryQueryAdapter
        implements MaskingInventoryQueryRepository, AutoCloseable {
    private static final String SUMMARY_SELECT = """
            SELECT rv.ruleset_version_id, r.code, r.name, rv.version_number, rv.status,
                   TO_CHAR(rv.created_at, 'YYYY-MM-DD"T"HH24:MI:SS.FF3TZH:TZM') created_at,
                   (SELECT COUNT(*) FROM masking_rule mr
                     WHERE mr.ruleset_version_id = rv.ruleset_version_id) column_rule_count,
                   (SELECT COUNT(*) FROM table_masking_rule tr
                     WHERE tr.ruleset_version_id = rv.ruleset_version_id) table_rule_count,
                   (SELECT COUNT(*) FROM transformation_mapping tm
                     JOIN transformation_import ti
                       ON ti.transformation_import_id = tm.transformation_import_id
                    WHERE ti.ruleset_version_id = rv.ruleset_version_id
                      AND tm.match_status IN ('UNRESOLVED','REVIEW_REQUIRED')) review_item_count,
                   NVL((SELECT MAX(ds.name) FROM transformation_import ti
                        JOIN data_source ds ON ds.data_source_id = ti.data_source_id
                       WHERE ti.ruleset_version_id = rv.ruleset_version_id), '') data_source_name,
                   NVL((SELECT MAX(ti.import_reference) FROM transformation_import ti
                       WHERE ti.ruleset_version_id = rv.ruleset_version_id), '') import_reference
              FROM ruleset r JOIN ruleset_version rv ON rv.ruleset_id = r.ruleset_id
            """;

    private final String jdbcUrl;
    private final String username;
    private final char[] password;

    public OracleMaskingInventoryQueryAdapter(String jdbcUrl, String username, char[] password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password").clone();
    }

    @Override
    public List<MaskingInventorySummary> list() {
        String sql = SUMMARY_SELECT + " ORDER BY rv.created_at DESC, rv.ruleset_version_id DESC";
        List<MaskingInventorySummary> inventories = new ArrayList<>();
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            while (row.next()) inventories.add(summary(row));
            return inventories;
        } catch (SQLException error) {
            throw failure("list masking inventories", error);
        }
    }

    @Override
    public MaskingInventoryDetails get(long rulesetVersionId) {
        String inventorySql = SUMMARY_SELECT + " WHERE rv.ruleset_version_id = ?";
        String mappingSql = """
                SELECT tm.transformation_mapping_id, tm.statement_number, tm.execution_order,
                       tm.target_type, tm.operation_type, tm.target_schema_name,
                       tm.target_table_name, tm.target_column_name,
                       tm.detected_algorithm_code, tm.match_status, tm.diagnostic_message,
                       tm.parameters_json, tm.filter_expression, tm.source_expression,
                       NVL(c.host_name, '') instance_name, NVL(c.service_name, '') database_name,
                       NVL(cc.native_data_type, '') data_type,
                       NVL(cc.nullable, 'Y') nullable,
                       CASE WHEN NVL(mr.enabled, tr.enabled) = 'N' THEN 'N' ELSE 'Y' END enabled,
                       CASE WHEN tm.target_type = 'TABLE' THEN 'CLEANUP'
                            WHEN a.category = 'LOOKUP' THEN 'LOOKUP' ELSE 'MASK' END algorithm_type
                  FROM transformation_mapping tm
                  JOIN transformation_import ti
                    ON ti.transformation_import_id = tm.transformation_import_id
                  JOIN data_source ds ON ds.data_source_id = ti.data_source_id
                  JOIN connector c ON c.connector_id = ds.connector_id
                  LEFT JOIN catalog_column cc ON cc.catalog_column_id = tm.catalog_column_id
                  LEFT JOIN masking_rule mr
                    ON mr.ruleset_version_id = ti.ruleset_version_id
                   AND mr.catalog_column_id = tm.catalog_column_id
                  LEFT JOIN table_masking_rule tr
                    ON tr.ruleset_version_id = ti.ruleset_version_id
                   AND tr.catalog_table_id = tm.catalog_table_id
                  LEFT JOIN algorithm_version av
                    ON av.algorithm_version_id = tm.algorithm_version_id
                  LEFT JOIN algorithm a ON a.algorithm_id = av.algorithm_id
                 WHERE ti.ruleset_version_id = ?
                 ORDER BY tm.execution_order, tm.transformation_mapping_id
                """;
        try (Connection connection = open();
                PreparedStatement inventoryStatement = connection.prepareStatement(inventorySql)) {
            inventoryStatement.setLong(1, rulesetVersionId);
            MaskingInventorySummary inventory;
            try (ResultSet row = inventoryStatement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException(
                        "Masking inventory was not found: " + rulesetVersionId);
                inventory = summary(row);
            }
            List<MaskingInventoryMapping> mappings = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(mappingSql)) {
                statement.setLong(1, rulesetVersionId);
                try (ResultSet row = statement.executeQuery()) {
                    while (row.next()) {
                        mappings.add(new MaskingInventoryMapping(
                                row.getLong("transformation_mapping_id"),
                                row.getInt("statement_number"), row.getInt("execution_order"),
                                value(row, "target_type"), value(row, "operation_type"),
                                value(row, "target_schema_name"), value(row, "target_table_name"),
                                value(row, "target_column_name"),
                                value(row, "detected_algorithm_code"), value(row, "match_status"),
                                value(row, "diagnostic_message"), value(row, "parameters_json"),
                                value(row, "filter_expression"), value(row, "source_expression"),
                                value(row, "instance_name"), value(row, "database_name"),
                                value(row, "data_type"), "Y".equals(value(row, "nullable")),
                                "Y".equals(value(row, "enabled")), value(row, "algorithm_type")));
                    }
                }
            }
            return new MaskingInventoryDetails(inventory, mappings);
        } catch (SQLException error) {
            throw failure("load masking inventory", error);
        }
    }

    private static MaskingInventorySummary summary(ResultSet row) throws SQLException {
        return new MaskingInventorySummary(row.getLong("ruleset_version_id"),
                value(row, "code"), value(row, "name"), row.getInt("version_number"),
                value(row, "status"), value(row, "created_at"),
                row.getInt("column_rule_count"), row.getInt("table_rule_count"),
                row.getInt("review_item_count"), value(row, "data_source_name"),
                value(row, "import_reference"));
    }

    private Connection open() throws SQLException {
        Connection connection = com.privonym.repository.RepositoryConnections.open(jdbcUrl, username, new String(password));
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        return connection;
    }

    private static String value(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? "" : value;
    }

    private static IllegalStateException failure(String action, SQLException error) {
        return new IllegalStateException("Unable to " + action + ": " + error.getMessage(), error);
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }
}
