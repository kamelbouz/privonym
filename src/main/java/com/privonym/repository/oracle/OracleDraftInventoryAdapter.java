package com.privonym.repository.oracle;

import com.privonym.application.port.out.DraftInventoryRepository;
import com.privonym.application.service.DraftInventoryResult;
import com.privonym.application.service.SqlInventoryColumn;
import com.privonym.application.service.SqlInventoryPreview;
import com.privonym.application.service.SqlInventoryTable;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Creates a draft masking inventory from a reviewed Transformer preview. */
public final class OracleDraftInventoryAdapter implements DraftInventoryRepository, AutoCloseable {
    private final String jdbcUrl;
    private final String username;
    private final char[] password;

    public OracleDraftInventoryAdapter(String jdbcUrl, String username, char[] password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password").clone();
    }

    @Override
    public DraftInventoryResult create(long dataSourceId, String inventoryCode,
            String inventoryName, String sourceSql, SqlInventoryPreview preview,
            String createdBy) {
        return createInternal(dataSourceId,inventoryCode,inventoryName,sourceSql,preview,createdBy,-1,0);
    }

    @Override public DraftInventoryResult createAuthorized(long dataSourceId,String inventoryCode,
            String inventoryName,String sourceSql,SqlInventoryPreview preview,String createdBy,long expectedRulesetId,long expectedOwnerUserId) {
        if(expectedRulesetId<0||expectedOwnerUserId<0)throw new IllegalArgumentException("Expected Rule Set and owner must be explicit");
        return createInternal(dataSourceId,inventoryCode,inventoryName,sourceSql,preview,createdBy,expectedRulesetId,expectedOwnerUserId);
    }
    private DraftInventoryResult createInternal(long dataSourceId,String inventoryCode,
            String inventoryName,String sourceSql,SqlInventoryPreview preview,String createdBy,long expectedRulesetId,long expectedOwnerUserId) {
        if(preview.warnings().stream().anyMatch(w->w.startsWith("[UNSUPPORTED]")))throw new IllegalArgumentException("Import contains unsupported business DDL/control flow or correlated predicates; no partial Rule Set was created. Review the preview report.");
        String reference = "TRANSFORM-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 18).toUpperCase(Locale.ROOT);
        try (Connection connection = open()) {
            try {
                Source source = loadSource(connection, dataSourceId);
                Ruleset ruleset = ensureRuleset(connection, dataSourceId,
                        inventoryCode, inventoryName, createdBy,expectedRulesetId,expectedOwnerUserId);
                Version version = createVersion(connection, ruleset.id(), createdBy);
                long importId = insertImport(connection, dataSourceId, version.id(), reference,
                        sourceSql, preview, createdBy);

                Counters counters = createMappingsAndRules(connection, importId,
                        dataSourceId, source.defaultSchema(), version.id(), preview);
                int reviewItems = counters.reviewItems() + (int)preview.warnings().stream().filter(w->!w.startsWith("[INFO]")&&!w.startsWith("[IGNORED]")).count();
                String status = reviewItems == 0 ? "DRAFT_CREATED" : "REVIEW_REQUIRED";
                finishImport(connection, importId, status, counters, reviewItems);
                connection.commit();
                return new DraftInventoryResult(importId, reference, version.id(), ruleset.code(),
                        version.number(), status, counters.columnRules(), counters.tableRules(),
                        reviewItems, preview.checksum());
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to create draft inventory: " + error.getMessage(), error);
        }
    }

    private static Source loadSource(Connection connection, long dataSourceId) throws SQLException {
        String sql = """
                SELECT c.technology_code,
                       NVL(c.schema_name, NVL(c.username_value, 'IMPORT')) default_schema
                  FROM data_source d JOIN connector c ON c.connector_id = d.connector_id
                 WHERE d.data_source_id = ? AND d.enabled = 'Y'
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, dataSourceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Data source was not found or is disabled");
                if (!Set.of("ORACLE", "POSTGRESQL", "SQLSERVER").contains(row.getString(1))) {
                    throw new IllegalArgumentException(
                            "CSV inventory import does not support target technology: "
                                    + row.getString(1));
                }
                return new Source(row.getString(2));
            }
        }
    }

    private static Ruleset ensureRuleset(Connection connection, long dataSourceId,
            String code, String name, String owner,long expectedRulesetId,long expectedOwnerUserId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT data_source_id FROM data_source WHERE data_source_id = ? FOR UPDATE")) {
            statement.setLong(1, dataSourceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Data source was not found");
            }
        }
        // One connection owns one Rule Set. CSV RULESET_NAME is descriptive input and
        // must never create a second Rule Set for an already-bound connection.
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ruleset_id, code FROM ruleset
                 WHERE data_source_id = ? AND (NOT EXISTS(SELECT 1 FROM connection_ruleset_binding b WHERE b.data_source_id=ruleset.data_source_id) OR EXISTS(SELECT 1 FROM connection_ruleset_binding b WHERE b.data_source_id=ruleset.data_source_id AND b.ruleset_id=ruleset.ruleset_id))
                 ORDER BY ruleset_id FETCH FIRST 2 ROWS ONLY
                """)) {
            statement.setLong(1, dataSourceId);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    long id=row.getLong(1);String existingCode=row.getString(2);
                    if(row.next())throw new IllegalStateException("Several Rule Sets exist for this connection; reconcile them before importing");
                    requireExpectedRuleset(expectedRulesetId,id);
                    if(expectedRulesetId>=0) {
                        try(PreparedStatement ownership=connection.prepareStatement("SELECT owner_user_id FROM ruleset WHERE ruleset_id=? FOR UPDATE")) {
                            ownership.setLong(1,id);
                            try(ResultSet current=ownership.executeQuery()) {
                                if(!current.next())throw new IllegalStateException("Rule Set changed after authorization");
                                requireExpectedOwner(expectedOwnerUserId,current.getLong(1));
                            }
                        }
                    }
                    return new Ruleset(id,existingCode);
                }
            }
        }
        requireExpectedRuleset(expectedRulesetId,0);
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 200) {
            throw new IllegalArgumentException(
                    "A Rule Set name containing 1 to 200 characters is required for this connection");
        }
        String normalizedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (normalizedCode.isEmpty()) normalizedCode = generatedRulesetCode(normalizedName, dataSourceId);
        if (!normalizedCode.matches("[A-Z][A-Z0-9_-]{2,99}")) {
            throw new IllegalArgumentException(
                    "Rule Set code must contain 3 to 100 letters, digits, hyphens or underscores");
        }
        String merge = """
                MERGE INTO ruleset target
                USING (SELECT ? data_source_id, ? code FROM dual) source
                   ON (target.data_source_id = source.data_source_id)
                 WHEN NOT MATCHED THEN INSERT (data_source_id, code, name, description, owner_name)
                      VALUES (source.data_source_id, source.code, ?,
                              'Created by Transformer or CSV import', ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(merge)) {
            statement.setLong(1, dataSourceId);
            statement.setString(2, normalizedCode);
            statement.setString(3, normalizedName);
            statement.setString(4, owner);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ruleset_id FROM ruleset WHERE code = ? AND data_source_id = ?")) {
            statement.setString(1, normalizedCode);
            statement.setLong(2, dataSourceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException(
                        "Rule Set " + normalizedCode + " belongs to another data source");
                return new Ruleset(row.getLong(1), normalizedCode);
            }
        }
    }

    private static String generatedRulesetCode(String name, long dataSourceId) {
        String base = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) base = "RULESET_" + base;
        String suffix = "_" + dataSourceId;
        int maxBaseLength = 100 - suffix.length();
        if (base.length() > maxBaseLength) base = base.substring(0, maxBaseLength);
        return base + suffix;
    }

    static void requireExpectedRuleset(long expected,long actual) {
        if(expected>=0&&expected!=actual)throw new IllegalStateException("Connection Rule Set changed after authorization; analyze again");
    }
    static void requireExpectedOwner(long expected,long actual) {
        if(expected>0&&expected!=actual)throw new IllegalStateException("Rule Set owner changed after authorization; import rejected");
    }

    private static Version createVersion(Connection connection, long rulesetId, String createdBy)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ruleset_id FROM ruleset WHERE ruleset_id = ? FOR UPDATE
                """)) {
            statement.setLong(1, rulesetId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Unable to lock Rule Set before creating its draft");
            }
        }
        try(PreparedStatement q=connection.prepareStatement("SELECT COUNT(*) FROM ruleset_version WHERE ruleset_id=? AND status='DRAFT'")) {
            q.setLong(1,rulesetId);try(ResultSet r=q.executeQuery()){r.next();if(r.getLong(1)>0)throw new IllegalStateException("This Rule Set already has a DRAFT version; open it before importing another version");}
        }
        int versionNumber;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT NVL(MAX(version_number), 0) + 1 FROM ruleset_version WHERE ruleset_id = ?")) {
            statement.setLong(1, rulesetId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                versionNumber = row.getInt(1);
            }
        }
        String insert = """
                INSERT INTO ruleset_version (
                    ruleset_id, version_number, status, description, created_by)
                VALUES (?, ?, 'DRAFT', 'Draft generated by Transformer', ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setLong(1, rulesetId);
            statement.setInt(2, versionNumber);
            statement.setString(3, createdBy);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ruleset_version_id FROM ruleset_version
                 WHERE ruleset_id = ? AND version_number = ?
                """)) {
            statement.setLong(1, rulesetId);
            statement.setInt(2, versionNumber);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Unable to reload draft inventory version");
                return new Version(row.getLong(1), versionNumber);
            }
        }
    }

    private static long insertImport(Connection connection, long dataSourceId, long versionId,
            String reference, String sourceSql, SqlInventoryPreview preview, String createdBy)
            throws SQLException {
        String insert = """
                INSERT INTO transformation_import (
                    data_source_id, ruleset_version_id, import_reference, source_type,
                    source_name, source_sql, source_checksum, status, statement_count,
                    matched_count, warning_count, unresolved_count, created_by, analyzed_at)
                VALUES (?, ?, ?, 'PASTED_SQL', 'Privonym web console', ?, ?, 'ANALYZED',
                        ?, 0, ?, 0, ?, SYSTIMESTAMP)
                """;
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setLong(1, dataSourceId);
            statement.setLong(2, versionId);
            statement.setString(3, reference);
            statement.setCharacterStream(4, new StringReader(sourceSql), sourceSql.length());
            statement.setString(5, preview.checksum());
            statement.setInt(6, preview.statementCount());
            statement.setInt(7, preview.warnings().size());
            statement.setString(8, createdBy);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT transformation_import_id FROM transformation_import WHERE import_reference = ?")) {
            statement.setString(1, reference);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Unable to reload Transformer import");
                return row.getLong(1);
            }
        }
    }

    private static Counters createMappingsAndRules(Connection connection, long importId,
            long dataSourceId, String defaultSchema, long versionId,
            SqlInventoryPreview preview) throws SQLException {
        int order = 0;
        int columnRules = 0;
        int tableRules = 0;
        int reviewItems = 0;
        Set<Long> usedColumns = new HashSet<>();
        Set<Long> usedTables = new HashSet<>();
        for (int tableIndex = 0; tableIndex < preview.tables().size(); tableIndex++) {
            SqlInventoryTable item = preview.tables().get(tableIndex);
            int statementNumber = tableIndex + 1;
            String schema = item.schema().isBlank() ? defaultSchema : item.schema();
            Long tableId = findTable(connection, dataSourceId, schema, item.name());
            // Inventory CSV files are portable between connections. TABLE_OWNER records
            // the source owner, while the selected connection's configured schema is the
            // authoritative target. Prefer an exact owner match, then transparently map
            // the same table to that target schema.
            if (tableId == null && !schema.equalsIgnoreCase(defaultSchema)) {
                schema = defaultSchema;
                tableId = findTable(connection, dataSourceId, schema, item.name());
            }
            if ("UPDATE".equals(item.operation())) {
                for (SqlInventoryColumn column : item.columns()) {
                    order += 10;
                    Long columnId = tableId == null ? null
                            : findColumn(connection, tableId, column.name());
                    Long algorithmId = findAlgorithmVersion(connection, column.algorithmCode(), false);
                    boolean duplicate = columnId != null && !usedColumns.add(columnId);
                    boolean resolved = "MATCHED".equals(column.matchStatus())
                            && tableId != null && columnId != null && algorithmId != null && !duplicate;
                    String status = resolved ? "MATCHED" : "UNRESOLVED";
                    String diagnostic = diagnostic(tableId, columnId, algorithmId, duplicate,
                            column.matchStatus());
                    replaceImportedMapping(connection, versionId, importId, "COLUMN", schema,
                            item.name(), column.name());
                    insertMapping(connection, importId, statementNumber, order, "COLUMN", "UPDATE",
                            schema, item.name(), column.name(), tableId, columnId,
                            column.sourceExpression(), item.filterExpression(), column.algorithmCode(),
                            algorithmId, parametersJson(column.parameters()), status, diagnostic);
                    if (resolved) {
                        long ruleId = upsertColumnRule(connection, versionId, columnId,
                                algorithmId, order, item.filterExpression(), column.enabled());
                        deleteRuleParameters(connection, ruleId);
                        insertParameters(connection, ruleId, algorithmId, column.parameters());
                        columnRules++;
                    } else {
                        reviewItems++;
                    }
                }
            } else {
                order += 10;
                Long algorithmId = findAlgorithmVersion(connection, item.algorithmCode(), true);
                boolean duplicate = tableId != null && !usedTables.add(tableId);
                boolean resolved = tableId != null && algorithmId != null && !duplicate;
                boolean destructiveReview = resolved;
                String status = resolved ? "REVIEW_REQUIRED" : "UNRESOLVED";
                String diagnostic = diagnostic(tableId, tableId, algorithmId, duplicate, "MATCHED");
                replaceImportedMapping(connection, versionId, importId, "TABLE", schema,
                        item.name(), null);
                insertMapping(connection, importId, statementNumber, order, "TABLE", item.operation(),
                        schema, item.name(), null, tableId, null, null, item.filterExpression(),
                        item.algorithmCode(), algorithmId, "{}", status,
                        destructiveReview ? "Destructive table operation requires approval" : diagnostic);
                if (resolved) {
                    upsertTableRule(connection, versionId, tableId, algorithmId,
                            order, item.filterExpression());
                    tableRules++;
                }
                reviewItems++;
            }
        }
        return new Counters(columnRules, tableRules, reviewItems);
    }

    private static Long findTable(Connection connection, long dataSourceId,
            String schema, String table) throws SQLException {
        String sql = """
                SELECT t.catalog_table_id FROM catalog_table t
                JOIN catalog_schema s ON s.catalog_schema_id = t.catalog_schema_id
                WHERE s.data_source_id = ?
                  AND UPPER(s.schema_name) = UPPER(?)
                  AND UPPER(t.table_name) = UPPER(?)
                ORDER BY CASE WHEN s.schema_name = ? AND t.table_name = ? THEN 0 ELSE 1 END
                FETCH FIRST 1 ROW ONLY
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, dataSourceId);
            statement.setString(2, schema);
            statement.setString(3, table);
            statement.setString(4, schema);
            statement.setString(5, table);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : null;
            }
        }
    }

    private static Long findColumn(Connection connection, long tableId, String column)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT catalog_column_id FROM catalog_column
                 WHERE catalog_table_id = ? AND UPPER(column_name) = UPPER(?)
                 ORDER BY CASE WHEN column_name = ? THEN 0 ELSE 1 END
                 FETCH FIRST 1 ROW ONLY
                """)) {
            statement.setLong(1, tableId);
            statement.setString(2, column);
            statement.setString(3, column);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : null;
            }
        }
    }

    private static Long findAlgorithmVersion(Connection connection, String code, boolean includeDraft)
            throws SQLException {
        if (code == null || code.isBlank() || "UNRESOLVED".equals(code)) return null;
        String sql = includeDraft ? """
                SELECT av.algorithm_version_id FROM algorithm a
                JOIN algorithm_version av ON av.algorithm_id = a.algorithm_id
                WHERE a.code = ? AND av.status IN ('PUBLISHED','DRAFT')
                ORDER BY CASE av.status WHEN 'PUBLISHED' THEN 0 ELSE 1 END, av.version_number DESC
                FETCH FIRST 1 ROW ONLY
                """ : """
                SELECT av.algorithm_version_id FROM algorithm a
                JOIN algorithm_version av ON av.algorithm_id = a.algorithm_id
                WHERE a.code = ? AND av.status = 'PUBLISHED'
                ORDER BY av.version_number DESC FETCH FIRST 1 ROW ONLY
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : null;
            }
        }
    }

    private static long upsertColumnRule(Connection connection, long versionId, long columnId,
            long algorithmId, int order, String filter, boolean enabled) throws SQLException {
        String sql = """
                MERGE INTO masking_rule target
                USING (SELECT ? ruleset_version_id, ? catalog_column_id FROM dual) source
                   ON (target.ruleset_version_id = source.ruleset_version_id
                       AND target.catalog_column_id = source.catalog_column_id)
                 WHEN MATCHED THEN UPDATE SET
                      target.algorithm_version_id = ?, target.execution_order = ?,
                      target.filter_expression = ?, target.enabled = ?
                 WHEN NOT MATCHED THEN INSERT (
                      ruleset_version_id, catalog_column_id, algorithm_version_id,
                      execution_order, filter_expression, enabled)
                      VALUES (source.ruleset_version_id, source.catalog_column_id, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, versionId);
            statement.setLong(2, columnId);
            statement.setLong(3, algorithmId);
            statement.setInt(4, order);
            setNullableClob(statement, 5, filter);
            statement.setString(6, enabled ? "Y" : "N");
            statement.setLong(7, algorithmId);
            statement.setInt(8, order);
            setNullableClob(statement, 9, filter);
            statement.setString(10, enabled ? "Y" : "N");
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rule_id FROM masking_rule
                 WHERE ruleset_version_id = ? AND catalog_column_id = ?
                """)) {
            statement.setLong(1, versionId);
            statement.setLong(2, columnId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Unable to reload masking rule");
                return row.getLong(1);
            }
        }
    }

    private static void upsertTableRule(Connection connection, long versionId, long tableId,
            long algorithmId, int order, String filter) throws SQLException {
        String sql = """
                MERGE INTO table_masking_rule target
                USING (SELECT ? ruleset_version_id, ? catalog_table_id FROM dual) source
                   ON (target.ruleset_version_id = source.ruleset_version_id
                       AND target.catalog_table_id = source.catalog_table_id)
                 WHEN MATCHED THEN UPDATE SET
                      target.algorithm_version_id = ?, target.execution_order = ?,
                      target.filter_expression = ?, target.enabled = 'Y'
                 WHEN NOT MATCHED THEN INSERT (
                      ruleset_version_id, catalog_table_id, algorithm_version_id,
                      execution_order, filter_expression, enabled)
                      VALUES (source.ruleset_version_id, source.catalog_table_id, ?, ?, ?, 'Y')
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, versionId);
            statement.setLong(2, tableId);
            statement.setLong(3, algorithmId);
            statement.setInt(4, order);
            setNullableClob(statement, 5, filter);
            statement.setLong(6, algorithmId);
            statement.setInt(7, order);
            setNullableClob(statement, 8, filter);
            statement.executeUpdate();
        }
    }

    private static void deleteRuleParameters(Connection connection, long ruleId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM rule_parameter WHERE rule_id = ?")) {
            statement.setLong(1, ruleId);
            statement.executeUpdate();
        }
    }

    private static void replaceImportedMapping(Connection connection, long versionId,
            long currentImportId, String targetType, String schema, String table, String column)
            throws SQLException {
        String sql = """
                DELETE FROM transformation_mapping target
                 WHERE target.transformation_import_id IN (
                       SELECT transformation_import_id FROM transformation_import
                        WHERE ruleset_version_id = ?)
                   AND target.target_type = ?
                   AND UPPER(target.target_schema_name) = UPPER(?)
                   AND UPPER(target.target_table_name) = UPPER(?)
                   AND ((? IS NULL AND target.target_column_name IS NULL)
                        OR UPPER(target.target_column_name) = UPPER(?))
                   AND target.transformation_import_id <> ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, versionId);
            statement.setString(2, targetType);
            statement.setString(3, schema);
            statement.setString(4, table);
            setNullableString(statement, 5, column);
            setNullableString(statement, 6, column);
            statement.setLong(7, currentImportId);
            statement.executeUpdate();
        }
    }

    private static void insertParameters(Connection connection, long ruleId, long algorithmId,
            Map<String, String> parameters) throws SQLException {
        String definition = """
                SELECT parameter_definition_id FROM algorithm_parameter_def
                 WHERE algorithm_version_id = ? AND parameter_name = ?
                """;
        String insert = """
                INSERT INTO rule_parameter (rule_id, parameter_definition_id, parameter_value)
                VALUES (?, ?, ?)
                """;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            Long definitionId = null;
            try (PreparedStatement statement = connection.prepareStatement(definition)) {
                statement.setLong(1, algorithmId);
                statement.setString(2, parameter.getKey());
                try (ResultSet row = statement.executeQuery()) {
                    if (row.next()) definitionId = row.getLong(1);
                }
            }
            if (definitionId == null) continue;
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setLong(1, ruleId);
                statement.setLong(2, definitionId);
                statement.setCharacterStream(3, new StringReader(parameter.getValue()),
                        parameter.getValue().length());
                statement.executeUpdate();
            }
        }
    }

    private static void insertMapping(Connection connection, long importId, int statementNumber,
            int order, String targetType, String operation, String schema, String table,
            String column, Long tableId, Long columnId, String expression, String filter,
            String algorithmCode, Long algorithmId, String parametersJson, String status,
            String diagnostic) throws SQLException {
        String sql = """
                INSERT INTO transformation_mapping (
                    transformation_import_id, statement_number, execution_order,
                    target_type, operation_type, target_schema_name, target_table_name,
                    target_column_name, catalog_table_id, catalog_column_id,
                    source_expression, filter_expression, detected_algorithm_code,
                    algorithm_version_id, parameters_json, match_status, diagnostic_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, importId);
            statement.setInt(2, statementNumber);
            statement.setInt(3, order);
            statement.setString(4, targetType);
            statement.setString(5, operation);
            statement.setString(6, schema);
            statement.setString(7, table);
            setNullableString(statement, 8, column);
            setNullableLong(statement, 9, tableId);
            setNullableLong(statement, 10, columnId);
            setNullableClob(statement, 11, expression);
            setNullableClob(statement, 12, filter);
            setNullableString(statement, 13, algorithmCode);
            setNullableLong(statement, 14, algorithmId);
            statement.setString(15, parametersJson);
            statement.setString(16, status);
            setNullableString(statement, 17, diagnostic);
            statement.executeUpdate();
        }
    }

    private static void finishImport(Connection connection, long importId, String status,
            Counters counters, int reviewItems) throws SQLException {
        String sql = """
                UPDATE transformation_import
                   SET status = ?, matched_count = ?, warning_count = ?,
                       unresolved_count = ?, draft_created_at = SYSTIMESTAMP
                 WHERE transformation_import_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setInt(2, counters.columnRules() + counters.tableRules());
            statement.setInt(3, reviewItems);
            statement.setInt(4, counters.reviewItems());
            statement.setLong(5, importId);
            statement.executeUpdate();
        }
    }

    private static String diagnostic(Long tableId, Long targetId, Long algorithmId,
            boolean duplicate, String parserStatus) {
        if (!"MATCHED".equals(parserStatus)) return "Algorithm expression was not recognized";
        if (tableId == null) return "Target table is absent from the selected data source inventory";
        if (targetId == null) return "Target column is absent from the selected data source inventory";
        if (algorithmId == null) return "Detected algorithm has no usable published version";
        if (duplicate) return "The target is assigned more than once in the imported script";
        return null;
    }

    private static String parametersJson(Map<String, String> parameters) {
        StringBuilder json = new StringBuilder("{");
        int index = 0;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (index++ > 0) json.append(',');
            json.append('"').append(jsonEscape(parameter.getKey())).append("\":\"")
                    .append(jsonEscape(parameter.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.NUMERIC); else statement.setLong(index, value);
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null || value.isBlank()) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setNullableClob(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null || value.isBlank()) statement.setNull(index, Types.CLOB);
        else statement.setCharacterStream(index, new StringReader(value), value.length());
    }

    private Connection open() throws SQLException {
        Connection connection = com.privonym.repository.RepositoryConnections.open(jdbcUrl, username, new String(password));
        connection.setAutoCommit(false);
        return connection;
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }

    private record Source(String defaultSchema) { }
    private record Ruleset(long id, String code) { }
    private record Version(long id, int number) { }
    private record Counters(int columnRules, int tableRules, int reviewItems) { }
}
