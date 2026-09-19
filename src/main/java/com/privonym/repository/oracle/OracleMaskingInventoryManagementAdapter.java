package com.privonym.repository.oracle;

import com.privonym.application.port.out.MaskingInventoryManagementRepository;
import com.privonym.application.service.InventoryMutationResult;
import com.privonym.application.service.MaskingAlgorithmOption;
import com.privonym.application.service.AlgorithmCreationResult;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Transactional review and publication adapter for draft masking inventories. */
public final class OracleMaskingInventoryManagementAdapter
        implements MaskingInventoryManagementRepository, AutoCloseable {
    private final String jdbcUrl;
    private final String username;
    private final char[] password;

    public OracleMaskingInventoryManagementAdapter(String jdbcUrl, String username, char[] password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password").clone();
    }

    @Override
    public List<MaskingAlgorithmOption> listAlgorithms() {
        String sql = """
                SELECT a.code, a.name, a.category, av.algorithm_version_id, av.status,
                       apd.parameter_name,
                       NVL((SELECT MAX(ai.implementation_type) FROM algorithm_implementation ai
                             WHERE ai.algorithm_version_id=av.algorithm_version_id
                               AND ai.technology_code='ORACLE'),'SQL_EXPRESSION') implementation_type
                  FROM algorithm a JOIN algorithm_version av ON av.algorithm_id = a.algorithm_id
                  LEFT JOIN algorithm_parameter_def apd
                    ON apd.algorithm_version_id = av.algorithm_version_id
                 WHERE a.status = 'ACTIVE' AND av.status IN ('PUBLISHED','DRAFT')
                 ORDER BY a.code,
                          CASE av.status WHEN 'PUBLISHED' THEN 0 ELSE 1 END,
                          av.version_number DESC, apd.parameter_name
                """;
        Map<String, AlgorithmBuilder> algorithms = new LinkedHashMap<>();
        try (Connection connection = open(true);
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                String code = row.getString("code");
                long versionId = row.getLong("algorithm_version_id");
                AlgorithmBuilder builder = algorithms.get(code);
                if (builder == null) {
                    builder = new AlgorithmBuilder(versionId, code, row.getString("name"),
                            row.getString("category"), row.getString("status"),row.getString("implementation_type"));
                    algorithms.put(code, builder);
                }
                if (builder.versionId == versionId && row.getString("parameter_name") != null) {
                    builder.parameters.add(row.getString("parameter_name"));
                }
            }
            return algorithms.values().stream().map(AlgorithmBuilder::freeze).toList();
        } catch (SQLException error) {
            throw failure("list masking algorithms", error);
        }
    }

    @Override
    public AlgorithmCreationResult cloneAlgorithm(String templateCode, String code, String name,
            List<String> lookupValues, String createdBy) {
        String templateSql = """
                SELECT a.algorithm_id,av.algorithm_version_id,ai.implementation_type
                  FROM algorithm a JOIN algorithm_version av ON av.algorithm_id=a.algorithm_id
                  JOIN algorithm_implementation ai ON ai.algorithm_version_id=av.algorithm_version_id
                 WHERE a.code=? AND a.status='ACTIVE' AND av.status='PUBLISHED'
                   AND ai.technology_code='ORACLE' AND ai.status='PUBLISHED'
                 ORDER BY av.version_number DESC FETCH FIRST 1 ROW ONLY
                """;
        try (Connection connection = open(false)) {
            try {
                long templateAlgorithmId, templateVersionId;
                String implementationType;
                try (PreparedStatement statement=connection.prepareStatement(templateSql)) {
                    statement.setString(1,templateCode);
                    try(ResultSet row=statement.executeQuery()) {
                        if(!row.next())throw new IllegalArgumentException("Published template algorithm was not found: "+templateCode);
                        templateAlgorithmId=row.getLong(1);templateVersionId=row.getLong(2);implementationType=row.getString(3);
                    }
                }
                boolean lookup="LOOKUP_MAPPING".equals(implementationType);
                if(lookup&&lookupValues.isEmpty())throw new IllegalArgumentException("A lookup algorithm requires at least one imported value");
                try(PreparedStatement statement=connection.prepareStatement("""
                        INSERT INTO algorithm(code,name,category,description,deterministic,reversible,
                          preserve_null,preserve_format,preserve_length,preserve_uniqueness,status)
                        SELECT ?,?,category,description,deterministic,reversible,preserve_null,
                          preserve_format,preserve_length,preserve_uniqueness,'ACTIVE'
                          FROM algorithm WHERE algorithm_id=?
                        """)){statement.setString(1,code);statement.setString(2,name);statement.setLong(3,templateAlgorithmId);statement.executeUpdate();}
                long algorithmId;try(PreparedStatement statement=connection.prepareStatement("SELECT algorithm_id FROM algorithm WHERE code=?")){statement.setString(1,code);try(ResultSet row=statement.executeQuery()){row.next();algorithmId=row.getLong(1);}}
                try(PreparedStatement statement=connection.prepareStatement("INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at) VALUES(?,1,'PUBLISHED',?,SYSTIMESTAMP)")){statement.setLong(1,algorithmId);statement.setString(2,createdBy);statement.executeUpdate();}
                long versionId;try(PreparedStatement statement=connection.prepareStatement("SELECT algorithm_version_id FROM algorithm_version WHERE algorithm_id=? AND version_number=1")){statement.setLong(1,algorithmId);try(ResultSet row=statement.executeQuery()){row.next();versionId=row.getLong(1);}}
                try(PreparedStatement statement=connection.prepareStatement("""
                        INSERT INTO algorithm_parameter_def(algorithm_version_id,parameter_name,data_type,
                          required,default_value,validation_expression,description)
                        SELECT ?,parameter_name,data_type,required,default_value,validation_expression,description
                          FROM algorithm_parameter_def WHERE algorithm_version_id=?
                        """)){statement.setLong(1,versionId);statement.setLong(2,templateVersionId);statement.executeUpdate();}
                try(PreparedStatement statement=connection.prepareStatement("""
                        INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,
                          implementation_type,implementation_code,code_checksum,status,published_at)
                        SELECT ?,technology_code,implementation_type,implementation_code,code_checksum,
                          'PUBLISHED',SYSTIMESTAMP FROM algorithm_implementation
                         WHERE algorithm_version_id=? AND technology_code='ORACLE'
                        """)){statement.setLong(1,versionId);statement.setLong(2,templateVersionId);statement.executeUpdate();}
                if(lookup){String datasetCode=(code.length()>93?code.substring(0,93):code)+"_VALUES";try(PreparedStatement statement=connection.prepareStatement("INSERT INTO lookup_dataset(code,name,category,version_number,status,published_at) SELECT ?,?,category,1,'PUBLISHED',SYSTIMESTAMP FROM algorithm WHERE algorithm_id=?")){statement.setString(1,datasetCode);statement.setString(2,name+" values");statement.setLong(3,algorithmId);statement.executeUpdate();}long datasetId;try(PreparedStatement statement=connection.prepareStatement("SELECT lookup_dataset_id FROM lookup_dataset WHERE code=? AND version_number=1")){statement.setString(1,datasetCode);try(ResultSet row=statement.executeQuery()){row.next();datasetId=row.getLong(1);}}try(PreparedStatement statement=connection.prepareStatement("INSERT INTO lookup_dataset_value(lookup_dataset_id,value_text,weight_value,enabled) VALUES(?,?,1,'Y')")){for(String value:lookupValues){statement.setLong(1,datasetId);statement.setString(2,value);statement.addBatch();}statement.executeBatch();}try(PreparedStatement statement=connection.prepareStatement("INSERT INTO algorithm_lookup_config(algorithm_version_id,lookup_dataset_id) VALUES(?,?)")){statement.setLong(1,versionId);statement.setLong(2,datasetId);statement.executeUpdate();}}
                connection.commit();return new AlgorithmCreationResult(algorithmId,versionId,code,name,implementationType,"PUBLISHED",lookup?lookupValues.size():0);
            } catch(SQLException|RuntimeException error){connection.rollback();throw error;}
        } catch(SQLException error){if(error.getErrorCode()==1)throw new IllegalArgumentException("Algorithm code already exists: "+code);throw failure("clone masking algorithm",error);}
    }

    @Override
    public InventoryMutationResult resolve(long versionId, long mappingId,
            String algorithmCode, String parametersText) {
        try (Connection connection = open(false)) {
            try {
                requireDraft(connection, versionId);
                Mapping mapping = resolveTarget(connection,
                        loadMapping(connection, versionId, mappingId), mappingId);
                boolean tableTarget = "TABLE".equals(mapping.targetType());
                Algorithm algorithm = loadAlgorithm(connection, algorithmCode, tableTarget);
                Map<String, String> parameters = parseParameters(parametersText);
                validateParameters(connection, algorithm.id(), parameters);
                if (tableTarget) {
                    if (mapping.tableId() == null) throw new IllegalArgumentException(
                            "Target table must be resolved before selecting an algorithm");
                    replaceTableRule(connection, versionId, mapping, algorithm.id());
                } else {
                    if (mapping.columnId() == null) throw new IllegalArgumentException(
                            "Target column must be resolved before selecting an algorithm");
                    long ruleId = replaceColumnRule(connection, versionId, mapping, algorithm.id());
                    replaceParameters(connection, ruleId, algorithm.id(), parameters);
                }
                String mappingStatus = tableTarget ? "REVIEW_REQUIRED" : "ACCEPTED";
                updateMapping(connection, mappingId, algorithmCode, algorithm.id(),
                        parametersJson(parameters), mappingStatus);
                int review = reviewCount(connection, versionId);
                connection.commit();
                return new InventoryMutationResult(versionId, "DRAFT", review,
                        tableTarget ? "Algorithm selected; structural approval is still required"
                                : "Column rule resolved");
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw failure("resolve inventory mapping", error);
        }
    }

    @Override
    public InventoryMutationResult resolveAll(long versionId) {
        try (Connection connection = open(false)) {
            try {
                requireDraft(connection, versionId);
                List<Long> mappingIds = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT tm.transformation_mapping_id
                          FROM transformation_mapping tm
                          JOIN transformation_import ti
                            ON ti.transformation_import_id=tm.transformation_import_id
                         WHERE ti.ruleset_version_id=?
                           AND tm.target_type='COLUMN'
                           AND tm.match_status IN ('UNRESOLVED','REVIEW_REQUIRED')
                           AND tm.algorithm_version_id IS NOT NULL
                           AND NOT EXISTS (
                               SELECT 1 FROM algorithm_parameter_def apd
                                WHERE apd.algorithm_version_id=tm.algorithm_version_id)
                         ORDER BY tm.execution_order,tm.transformation_mapping_id
                        """)) {
                    statement.setLong(1, versionId);
                    try (ResultSet row = statement.executeQuery()) {
                        while (row.next()) mappingIds.add(row.getLong(1));
                    }
                }
                int validated = 0;
                for (long mappingId : mappingIds) {
                    Mapping mapping = resolveTarget(connection,
                            loadMapping(connection, versionId, mappingId), mappingId);
                    if (mapping.columnId() == null || mapping.algorithmId() == null) continue;
                    replaceColumnRule(connection, versionId, mapping, mapping.algorithmId());
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE transformation_mapping
                               SET match_status='ACCEPTED', diagnostic_message=NULL
                             WHERE transformation_mapping_id=?
                            """)) {
                        statement.setLong(1, mappingId);
                        statement.executeUpdate();
                    }
                    validated++;
                }
                int review = reviewCount(connection, versionId);
                connection.commit();
                return new InventoryMutationResult(versionId, "DRAFT", review,
                        validated + " rule(s) validated automatically; " + review
                                + " item(s) still require individual review");
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw failure("validate inventory mappings in bulk", error);
        }
    }

    @Override
    public InventoryMutationResult accept(long versionId, long mappingId) {
        try (Connection connection = open(false)) {
            try {
                requireDraft(connection, versionId);
                Mapping mapping = loadMapping(connection, versionId, mappingId);
                if (!"TABLE".equals(mapping.targetType()) || mapping.algorithmId() == null
                        || mapping.tableId() == null) {
                    throw new IllegalArgumentException(
                            "Only a resolved table operation can be explicitly accepted");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE transformation_mapping SET match_status = 'ACCEPTED',
                               diagnostic_message = 'Destructive table operation explicitly accepted'
                         WHERE transformation_mapping_id = ?
                        """)) {
                    statement.setLong(1, mappingId);
                    statement.executeUpdate();
                }
                int review = reviewCount(connection, versionId);
                connection.commit();
                return new InventoryMutationResult(versionId, "DRAFT", review,
                        "Structural operation accepted");
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw failure("accept inventory mapping", error);
        }
    }

    @Override
    public InventoryMutationResult publish(long versionId) {
        try (Connection connection = open(false)) {
            try {
                requireDraft(connection, versionId);
                int review = reviewCount(connection, versionId);
                if (review != 0) throw new IllegalArgumentException(
                        "Inventory still contains " + review + " item(s) requiring review");
                int rules = ruleCount(connection, versionId);
                if (rules == 0) throw new IllegalArgumentException("Inventory contains no masking rule");
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE ruleset_version SET status = 'PUBLISHED', published_at = SYSTIMESTAMP
                         WHERE ruleset_version_id = ? AND status = 'DRAFT'
                        """)) {
                    statement.setLong(1, versionId);
                    if (statement.executeUpdate() != 1) throw new IllegalStateException(
                            "Draft inventory status changed concurrently");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE transformation_import SET status = 'PUBLISHED'
                         WHERE ruleset_version_id = ?
                        """)) {
                    statement.setLong(1, versionId);
                    statement.executeUpdate();
                } catch (SQLException error) {
                    if (error.getErrorCode() != 942) throw error;
                }
                connection.commit();
                return new InventoryMutationResult(versionId, "PUBLISHED", 0,
                        "Inventory published successfully");
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw failure("publish masking inventory", error);
        }
    }

    private static void requireDraft(Connection connection, long versionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status FROM ruleset_version WHERE ruleset_version_id = ? FOR UPDATE
                """)) {
            statement.setLong(1, versionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Inventory version was not found");
                if (!"DRAFT".equals(row.getString(1))) throw new IllegalArgumentException(
                        "Only a DRAFT inventory can be modified");
            }
        }
    }

    private static Mapping loadMapping(Connection connection, long versionId, long mappingId)
            throws SQLException {
        String sql = """
                SELECT tm.target_type, tm.operation_type, tm.catalog_table_id,
                       tm.catalog_column_id, tm.algorithm_version_id,
                       tm.execution_order, tm.filter_expression,
                       ti.data_source_id, c.schema_name default_schema,
                       tm.target_schema_name, tm.target_table_name, tm.target_column_name
                  FROM transformation_mapping tm JOIN transformation_import ti
                    ON ti.transformation_import_id = tm.transformation_import_id
                  JOIN data_source ds ON ds.data_source_id = ti.data_source_id
                  JOIN connector c ON c.connector_id = ds.connector_id
                 WHERE ti.ruleset_version_id = ? AND tm.transformation_mapping_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, versionId);
            statement.setLong(2, mappingId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Inventory mapping was not found");
                return new Mapping(row.getString("target_type"), row.getString("operation_type"),
                        nullableLong(row, "catalog_table_id"), nullableLong(row, "catalog_column_id"),
                        nullableLong(row, "algorithm_version_id"), row.getInt("execution_order"),
                        value(row, "filter_expression"), row.getLong("data_source_id"),
                        row.getString("default_schema"), row.getString("target_schema_name"),
                        row.getString("target_table_name"), row.getString("target_column_name"));
            }
        }
    }

    private static Mapping resolveTarget(Connection connection, Mapping mapping, long mappingId)
            throws SQLException {
        if (mapping.tableId() != null
                && ("TABLE".equals(mapping.targetType()) || mapping.columnId() != null)) return mapping;
        String schema = mapping.targetSchema();
        Long tableId = findCatalogTable(connection, mapping.dataSourceId(), schema,
                mapping.targetTable());
        if (tableId == null && mapping.defaultSchema() != null
                && !mapping.defaultSchema().equalsIgnoreCase(schema)) {
            schema = mapping.defaultSchema();
            tableId = findCatalogTable(connection, mapping.dataSourceId(), schema,
                    mapping.targetTable());
        }
        Long columnId = "TABLE".equals(mapping.targetType()) || tableId == null ? null
                : findCatalogColumn(connection, tableId, mapping.targetColumn());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transformation_mapping
                   SET target_schema_name = ?, catalog_table_id = ?, catalog_column_id = ?
                 WHERE transformation_mapping_id = ?
                """)) {
            statement.setString(1, schema);
            setNullableLong(statement, 2, tableId);
            setNullableLong(statement, 3, columnId);
            statement.setLong(4, mappingId);
            statement.executeUpdate();
        }
        return new Mapping(mapping.targetType(), mapping.operation(), tableId, columnId,
                mapping.algorithmId(), mapping.order(), mapping.filter(), mapping.dataSourceId(),
                mapping.defaultSchema(), schema, mapping.targetTable(), mapping.targetColumn());
    }

    private static Long findCatalogTable(Connection connection, long dataSourceId,
            String schema, String table) throws SQLException {
        if (schema == null || table == null) return null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ct.catalog_table_id
                  FROM catalog_table ct JOIN catalog_schema cs
                    ON cs.catalog_schema_id = ct.catalog_schema_id
                 WHERE cs.data_source_id = ? AND UPPER(cs.schema_name) = UPPER(?)
                   AND UPPER(ct.table_name) = UPPER(?)
                 ORDER BY CASE WHEN cs.schema_name = ? AND ct.table_name = ? THEN 0 ELSE 1 END
                 FETCH FIRST 1 ROW ONLY
                """)) {
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

    private static Long findCatalogColumn(Connection connection, long tableId, String column)
            throws SQLException {
        if (column == null) return null;
        try (PreparedStatement statement = connection.prepareStatement("""
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

    private static Algorithm loadAlgorithm(Connection connection, String code, boolean includeDraft)
            throws SQLException {
        String statuses = includeDraft ? "('PUBLISHED','DRAFT')" : "('PUBLISHED')";
        String sql = """
                SELECT av.algorithm_version_id, av.status FROM algorithm a
                JOIN algorithm_version av ON av.algorithm_id = a.algorithm_id
                WHERE a.code = ? AND a.status = 'ACTIVE' AND av.status IN %s
                ORDER BY CASE av.status WHEN 'PUBLISHED' THEN 0 ELSE 1 END,
                         av.version_number DESC FETCH FIRST 1 ROW ONLY
                """.formatted(statuses);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException(
                        "No usable algorithm version was found: " + code);
                return new Algorithm(row.getLong(1), row.getString(2));
            }
        }
    }

    private static void validateParameters(Connection connection, long algorithmId,
            Map<String, String> parameters) throws SQLException {
        Map<String, Boolean> definitions = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT parameter_name, required FROM algorithm_parameter_def
                 WHERE algorithm_version_id = ?
                """)) {
            statement.setLong(1, algorithmId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) definitions.put(row.getString(1), "Y".equals(row.getString(2)));
            }
        }
        for (String name : parameters.keySet()) {
            if (!definitions.containsKey(name)) throw new IllegalArgumentException(
                    "Unknown parameter for selected algorithm: " + name);
        }
        for (Map.Entry<String, Boolean> definition : definitions.entrySet()) {
            if (definition.getValue() && !parameters.containsKey(definition.getKey())) {
                throw new IllegalArgumentException("Required parameter is missing: " + definition.getKey());
            }
        }
    }

    private static long replaceColumnRule(Connection connection, long versionId,
            Mapping mapping, long algorithmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM masking_rule WHERE ruleset_version_id = ? AND catalog_column_id = ?
                """)) {
            statement.setLong(1, versionId);
            statement.setLong(2, mapping.columnId());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO masking_rule (ruleset_version_id, catalog_column_id,
                    algorithm_version_id, execution_order, filter_expression, enabled)
                VALUES (?, ?, ?, ?, ?, 'Y')
                """)) {
            statement.setLong(1, versionId);
            statement.setLong(2, mapping.columnId());
            statement.setLong(3, algorithmId);
            statement.setInt(4, mapping.order());
            setClob(statement, 5, mapping.filter());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rule_id FROM masking_rule WHERE ruleset_version_id = ? AND catalog_column_id = ?
                """)) {
            statement.setLong(1, versionId);
            statement.setLong(2, mapping.columnId());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static void replaceTableRule(Connection connection, long versionId,
            Mapping mapping, long algorithmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM table_masking_rule WHERE ruleset_version_id = ? AND catalog_table_id = ?
                """)) {
            statement.setLong(1, versionId);
            statement.setLong(2, mapping.tableId());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO table_masking_rule (ruleset_version_id, catalog_table_id,
                    algorithm_version_id, execution_order, filter_expression, enabled)
                VALUES (?, ?, ?, ?, ?, 'Y')
                """)) {
            statement.setLong(1, versionId);
            statement.setLong(2, mapping.tableId());
            statement.setLong(3, algorithmId);
            statement.setInt(4, mapping.order());
            setClob(statement, 5, mapping.filter());
            statement.executeUpdate();
        }
    }

    private static void replaceParameters(Connection connection, long ruleId, long algorithmId,
            Map<String, String> parameters) throws SQLException {
        String definitionSql = """
                SELECT parameter_definition_id FROM algorithm_parameter_def
                 WHERE algorithm_version_id = ? AND parameter_name = ?
                """;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            long definitionId;
            try (PreparedStatement statement = connection.prepareStatement(definitionSql)) {
                statement.setLong(1, algorithmId);
                statement.setString(2, parameter.getKey());
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException(
                            "Unknown algorithm parameter: " + parameter.getKey());
                    definitionId = row.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO rule_parameter (rule_id, parameter_definition_id, parameter_value)
                    VALUES (?, ?, ?)
                    """)) {
                statement.setLong(1, ruleId);
                statement.setLong(2, definitionId);
                statement.setCharacterStream(3, new StringReader(parameter.getValue()),
                        parameter.getValue().length());
                statement.executeUpdate();
            }
        }
    }

    private static void updateMapping(Connection connection, long mappingId, String algorithmCode,
            long algorithmId, String parametersJson, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transformation_mapping
                   SET detected_algorithm_code = ?, algorithm_version_id = ?,
                       parameters_json = ?, match_status = ?, diagnostic_message = NULL
                 WHERE transformation_mapping_id = ?
                """)) {
            statement.setString(1, algorithmCode);
            statement.setLong(2, algorithmId);
            statement.setString(3, parametersJson);
            statement.setString(4, status);
            statement.setLong(5, mappingId);
            statement.executeUpdate();
        }
    }

    private static int reviewCount(Connection connection, long versionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM transformation_mapping tm
                JOIN transformation_import ti ON ti.transformation_import_id = tm.transformation_import_id
                WHERE ti.ruleset_version_id = ?
                  AND tm.match_status IN ('UNRESOLVED','REVIEW_REQUIRED')
                """)) {
            statement.setLong(1, versionId);
            try (ResultSet row = statement.executeQuery()) { row.next(); return row.getInt(1); }
        } catch (SQLException error) {
            if (error.getErrorCode() == 942) return 0;
            throw error;
        }
    }

    private static int ruleCount(Connection connection, long versionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT (SELECT COUNT(*) FROM masking_rule WHERE ruleset_version_id = ?)
                     + (SELECT COUNT(*) FROM table_masking_rule WHERE ruleset_version_id = ?)
                  FROM dual
                """)) {
            statement.setLong(1, versionId);
            statement.setLong(2, versionId);
            try (ResultSet row = statement.executeQuery()) { row.next(); return row.getInt(1); }
        } catch (SQLException error) {
            if (error.getErrorCode() != 942) throw error;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM masking_rule WHERE ruleset_version_id = ?")) {
                statement.setLong(1, versionId);
                try (ResultSet row = statement.executeQuery()) { row.next(); return row.getInt(1); }
            }
        }
    }

    private static Map<String, String> parseParameters(String text) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return parameters;
        for (String item : text.split(",")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length != 2 || !pair[0].trim().matches("[a-z][a-z0-9_]{0,99}")) {
                throw new IllegalArgumentException("Parameters must use name=value pairs separated by commas");
            }
            parameters.put(pair[0].trim(), pair[1].trim());
        }
        return parameters;
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
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.NUMERIC);
        else statement.setLong(index, value);
    }

    private static String value(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? "" : value;
    }

    private static void setClob(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null || value.isBlank()) statement.setNull(index, Types.CLOB);
        else statement.setCharacterStream(index, new StringReader(value), value.length());
    }

    private Connection open(boolean readOnly) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, username, new String(password));
        connection.setReadOnly(readOnly);
        connection.setAutoCommit(false);
        return connection;
    }

    private static IllegalStateException failure(String action, SQLException error) {
        return new IllegalStateException("Unable to " + action + ": " + error.getMessage(), error);
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }

    private record Mapping(String targetType, String operation, Long tableId, Long columnId,
            Long algorithmId, int order, String filter, long dataSourceId, String defaultSchema,
            String targetSchema, String targetTable, String targetColumn) { }
    private record Algorithm(long id, String status) { }

    private static final class AlgorithmBuilder {
        private final long versionId;
        private final String code;
        private final String name;
        private final String category;
        private final String status;
        private final String implementationType;
        private final List<String> parameters = new ArrayList<>();

        private AlgorithmBuilder(long versionId, String code, String name,
                String category, String status,String implementationType) {
            this.versionId = versionId;
            this.code = code;
            this.name = name;
            this.category = category;
            this.status = status;
            this.implementationType=implementationType;
        }

        private MaskingAlgorithmOption freeze() {
            return new MaskingAlgorithmOption(code, name, category, status, parameters,implementationType);
        }
    }
}
