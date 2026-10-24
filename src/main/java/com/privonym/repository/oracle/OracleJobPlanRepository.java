package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.AlgorithmParameter;
import com.privonym.adapter.oracle.GeneratedOracleStatement;
import com.privonym.adapter.oracle.OracleMaskingAssignment;
import com.privonym.adapter.oracle.OracleQualifiedTable;
import com.privonym.adapter.oracle.OracleTemplateException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OracleJobPlanRepository {
    private static final String LOAD_RULES_SQL = """
            SELECT
                j.job_id,
                j.job_reference,
                j.status AS job_status,
                ct.catalog_table_id,
                cs.schema_name,
                ct.table_name,
                cc.column_name,
                cc.data_length,
                mr.rule_id,
                mr.execution_order,
                mr.filter_expression,
                alc.lookup_dataset_id,
                a.code AS algorithm_code,
                ai.implementation_type,
                ai.implementation_code,
                apd.parameter_name,
                apd.data_type AS parameter_type,
                apd.required AS parameter_required,
                apd.default_value,
                rp.parameter_value
            FROM anonymization_job j
            JOIN ruleset_version rv
              ON rv.ruleset_version_id = j.ruleset_version_id
             AND rv.status = 'PUBLISHED'
            JOIN masking_rule mr
              ON mr.ruleset_version_id = rv.ruleset_version_id
             AND mr.enabled = 'Y'
            JOIN catalog_column cc
              ON cc.catalog_column_id = mr.catalog_column_id
            JOIN catalog_table ct
              ON ct.catalog_table_id = cc.catalog_table_id
            JOIN catalog_schema cs
              ON cs.catalog_schema_id = ct.catalog_schema_id
            JOIN algorithm_version av
              ON av.algorithm_version_id = mr.algorithm_version_id
             AND av.status = 'PUBLISHED'
            JOIN algorithm a
              ON a.algorithm_id = av.algorithm_id
            JOIN algorithm_implementation ai
              ON ai.algorithm_version_id = av.algorithm_version_id
             AND ai.technology_code = ?
              AND ai.status = 'PUBLISHED'
            LEFT JOIN algorithm_lookup_config alc
              ON alc.algorithm_version_id = av.algorithm_version_id
            LEFT JOIN algorithm_parameter_def apd
              ON apd.algorithm_version_id = av.algorithm_version_id
            LEFT JOIN rule_parameter rp
              ON rp.rule_id = mr.rule_id
             AND rp.parameter_definition_id = apd.parameter_definition_id
            WHERE j.job_reference = ?
            ORDER BY ct.catalog_table_id, mr.execution_order, mr.rule_id,
                     apd.parameter_name
            """;

    private static final String SAVE_STEP_SQL = """
            UPDATE job_step
               SET generated_code = ?,
                   generated_code_checksum = ?,
                   status = 'READY'
             WHERE job_id = ?
               AND catalog_table_id = ?
               AND step_type = 'ANONYMIZE_TABLE'
            """;

    private static final String LOAD_TABLE_RULES_SQL = """
            SELECT j.job_id, j.job_reference, j.status AS job_status,
                   ct.catalog_table_id, cs.schema_name, ct.table_name,
                   a.code AS algorithm_code, tr.filter_expression
              FROM anonymization_job j
              JOIN ruleset_version rv ON rv.ruleset_version_id = j.ruleset_version_id
                   AND rv.status = 'PUBLISHED'
              JOIN table_masking_rule tr ON tr.ruleset_version_id = rv.ruleset_version_id
                   AND tr.enabled = 'Y'
              JOIN catalog_table ct ON ct.catalog_table_id = tr.catalog_table_id
              JOIN catalog_schema cs ON cs.catalog_schema_id = ct.catalog_schema_id
              JOIN algorithm_version av ON av.algorithm_version_id = tr.algorithm_version_id
                   AND av.status IN ('PUBLISHED','DRAFT')
              JOIN algorithm a ON a.algorithm_id = av.algorithm_id AND a.category = 'STRUCTURAL'
             WHERE j.job_reference = ?
             ORDER BY tr.execution_order, tr.table_rule_id
            """;

    private static final String LOAD_RULESET_RULES_SQL = """
            SELECT ct.catalog_table_id,cs.schema_name,ct.table_name,cc.column_name,
                   cc.data_length,
                   mr.rule_id,mr.execution_order,
                   DBMS_LOB.SUBSTR(mr.filter_expression,4000,1) filter_expression,
                   alc.lookup_dataset_id,a.code AS algorithm_code,
                   ai.implementation_type,ai.implementation_code,
                   apd.parameter_name,apd.data_type AS parameter_type,
                   apd.required AS parameter_required,apd.default_value,rp.parameter_value
              FROM ruleset_version rv
              JOIN masking_rule mr ON mr.ruleset_version_id=rv.ruleset_version_id
                   AND mr.enabled='Y'
              JOIN catalog_column cc ON cc.catalog_column_id=mr.catalog_column_id
              JOIN catalog_table ct ON ct.catalog_table_id=cc.catalog_table_id
              JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
              JOIN algorithm_version av ON av.algorithm_version_id=mr.algorithm_version_id
                   AND av.status='PUBLISHED'
              JOIN algorithm a ON a.algorithm_id=av.algorithm_id
              JOIN algorithm_implementation ai ON ai.algorithm_version_id=av.algorithm_version_id
                   AND ai.technology_code=? AND ai.status='PUBLISHED'
              LEFT JOIN algorithm_lookup_config alc ON alc.algorithm_version_id=av.algorithm_version_id
              LEFT JOIN algorithm_parameter_def apd ON apd.algorithm_version_id=av.algorithm_version_id
              LEFT JOIN rule_parameter rp ON rp.rule_id=mr.rule_id
                   AND rp.parameter_definition_id=apd.parameter_definition_id
             WHERE rv.ruleset_id=? AND rv.ruleset_version_id=?
               AND rv.status IN('DRAFT','PUBLISHED')
             ORDER BY ct.catalog_table_id,DBMS_LOB.SUBSTR(mr.filter_expression,4000,1),
                      mr.execution_order,mr.rule_id,
                      apd.parameter_name
            """;

    private static final String LOAD_RULESET_TABLE_RULES_SQL = """
            SELECT ct.catalog_table_id,cs.schema_name,ct.table_name,
                   a.code AS algorithm_code,tr.filter_expression
              FROM ruleset_version rv
              JOIN table_masking_rule tr ON tr.ruleset_version_id=rv.ruleset_version_id
                   AND tr.enabled='Y'
              JOIN catalog_table ct ON ct.catalog_table_id=tr.catalog_table_id
              JOIN catalog_schema cs ON cs.catalog_schema_id=ct.catalog_schema_id
              JOIN algorithm_version av ON av.algorithm_version_id=tr.algorithm_version_id
                   AND av.status IN('PUBLISHED','DRAFT')
              JOIN algorithm a ON a.algorithm_id=av.algorithm_id AND a.category='STRUCTURAL'
             WHERE rv.ruleset_id=? AND rv.ruleset_version_id=?
               AND rv.status IN('DRAFT','PUBLISHED')
             ORDER BY tr.execution_order,tr.table_rule_id
            """;

    public List<RepositoryTablePlan> load(Connection connection, String jobReference,
            String technologyCode)
            throws SQLException {
        Map<Long, MutableTablePlan> tables = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(LOAD_RULES_SQL)) {
            statement.setString(1, technologyCode);
            statement.setString(2, jobReference);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String jobStatus = rows.getString("job_status");
                    if (!List.of("CREATED", "READY", "PLANNING").contains(jobStatus)) {
                        throw new OracleTemplateException(
                                "Job cannot be previewed in status: " + jobStatus);
                    }
                    long tableId = rows.getLong("catalog_table_id");
                    MutableTablePlan table = tables.computeIfAbsent(
                            tableId,
                            ignored -> new MutableTablePlan(
                                    rowsUncheckedLong(rows, "job_id"),
                                    rowsUncheckedString(rows, "job_reference"),
                                    tableId,
                                    technologyCode,
                                    new OracleQualifiedTable(
                                            rowsUncheckedString(rows, "schema_name"),
                                            rowsUncheckedString(rows, "table_name"))));

                    long ruleId = rows.getLong("rule_id");
                    MutableAssignment assignment = table.assignments.computeIfAbsent(
                            ruleId,
                            ignored -> new MutableAssignment(
                                    rowsUncheckedString(rows, "column_name"),
                                    rowsUncheckedString(rows, "algorithm_code"),
                                    rowsUncheckedString(rows, "implementation_type"),
                                    rowsUncheckedString(rows, "implementation_code"),
                                    rowsUncheckedInt(rows, "execution_order"),
                                    rowsUncheckedNullableLong(rows, "lookup_dataset_id"),
                                    rowsUncheckedNullableInt(rows, "data_length")));

                    String filter = rows.getString("filter_expression");
                    assignment.filterExpression = filter == null ? "" : filter.trim();

                    String parameterName = rows.getString("parameter_name");
                    if (parameterName != null) {
                        AlgorithmParameter definition = OracleParameterPolicy.create(
                                assignment.algorithmCode,
                                parameterName,
                                rows.getString("parameter_type"),
                                "Y".equals(rows.getString("parameter_required")),
                                rows.getString("default_value"));
                        assignment.definitions.add(definition);
                        String value = rows.getString("parameter_value");
                        if (value != null) {
                            assignment.values.put(parameterName, value);
                        }
                    }
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(LOAD_TABLE_RULES_SQL)) {
            statement.setString(1, jobReference);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String jobStatus = rows.getString("job_status");
                    if (!List.of("CREATED", "READY", "PLANNING").contains(jobStatus)) {
                        throw new OracleTemplateException(
                                "Job cannot be previewed in status: " + jobStatus);
                    }
                    long tableId = rows.getLong("catalog_table_id");
                    MutableTablePlan table = tables.computeIfAbsent(tableId,
                            ignored -> new MutableTablePlan(rowsUncheckedLong(rows, "job_id"),
                                    rowsUncheckedString(rows, "job_reference"), tableId, technologyCode,
                                    new OracleQualifiedTable(
                                            rowsUncheckedString(rows, "schema_name"),
                                            rowsUncheckedString(rows, "table_name"))));
                    if (!table.assignments.isEmpty() || table.tableAlgorithmCode != null) {
                        throw new OracleTemplateException(
                                "Only one table-level algorithm can apply to table: " + table.table);
                    }
                    table.tableAlgorithmCode = rows.getString("algorithm_code");
                    table.filterExpression = rows.getString("filter_expression");
                }
            }
        }

        if (tables.isEmpty()) {
            throw new OracleTemplateException(
                    "No published " + technologyCode + " masking rules found for job: " + jobReference);
        }

        List<RepositoryTablePlan> result = new ArrayList<>();
        for (MutableTablePlan table : tables.values()) {
            table.applyColumnFilters();
            result.add(table.freeze(connection));
        }
        return List.copyOf(result);
    }

    public List<RepositoryTablePlan> loadRuleset(Connection connection, long rulesetId,
            long versionId, String technologyCode) throws SQLException {
        Map<String, MutableTablePlan> groups = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(LOAD_RULESET_RULES_SQL)) {
            statement.setString(1, technologyCode);
            statement.setLong(2, rulesetId);
            statement.setLong(3, versionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long tableId = rows.getLong("catalog_table_id");
                    String rawFilter = rows.getString("filter_expression");
                    final String filter = rawFilter == null ? "" : rawFilter.trim();
                    String key = tableId + "\u0000" + filter;
                    MutableTablePlan table = groups.computeIfAbsent(key, ignored -> {
                        MutableTablePlan value = new MutableTablePlan(0L,
                                "RULESET-" + versionId, tableId, technologyCode,
                                new OracleQualifiedTable(rowsUncheckedString(rows, "schema_name"),
                                        rowsUncheckedString(rows, "table_name")));
                        value.filterExpression = filter;
                        return value;
                    });
                    long ruleId = rows.getLong("rule_id");
                    MutableAssignment assignment = table.assignments.computeIfAbsent(ruleId,
                            ignored -> new MutableAssignment(
                                    rowsUncheckedString(rows, "column_name"),
                                    rowsUncheckedString(rows, "algorithm_code"),
                                    rowsUncheckedString(rows, "implementation_type"),
                                    rowsUncheckedString(rows, "implementation_code"),
                                    rowsUncheckedInt(rows, "execution_order"),
                                    rowsUncheckedNullableLong(rows, "lookup_dataset_id"),
                                    rowsUncheckedNullableInt(rows, "data_length")));
                    String parameterName = rows.getString("parameter_name");
                    if (parameterName != null) {
                        assignment.definitions.add(OracleParameterPolicy.create(
                                assignment.algorithmCode, parameterName,
                                rows.getString("parameter_type"),
                                "Y".equals(rows.getString("parameter_required")),
                                rows.getString("default_value")));
                        String value = rows.getString("parameter_value");
                        if (value != null) assignment.values.put(parameterName, value);
                    }
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                LOAD_RULESET_TABLE_RULES_SQL)) {
            statement.setLong(1, rulesetId);
            statement.setLong(2, versionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long tableId = rows.getLong("catalog_table_id");
                    if (groups.values().stream().anyMatch(item -> item.catalogTableId == tableId)) {
                        throw new OracleTemplateException(
                                "A structural table action cannot be combined with column updates: "
                                        + rows.getString("schema_name") + "." + rows.getString("table_name"));
                    }
                    MutableTablePlan table = new MutableTablePlan(0L, "RULESET-" + versionId,
                            tableId, technologyCode, new OracleQualifiedTable(
                                    rows.getString("schema_name"), rows.getString("table_name")));
                    table.tableAlgorithmCode = rows.getString("algorithm_code");
                    table.filterExpression = rows.getString("filter_expression");
                    groups.put(tableId + "\u0000TABLE", table);
                }
            }
        }
        if (groups.isEmpty()) {
            throw new OracleTemplateException("Rule Set version contains no enabled masking rule");
        }
        List<RepositoryTablePlan> result = new ArrayList<>();
        for (MutableTablePlan table : groups.values()) result.add(table.freeze(connection));
        return List.copyOf(result);
    }

    public void save(
            Connection connection,
            RepositoryTablePlan plan,
            GeneratedOracleStatement statement) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(SAVE_STEP_SQL)) {
            update.setString(1, ProtectedSql.store(statement.sql()));
            update.setString(2, statement.sha256());
            update.setLong(3, plan.jobId());
            update.setLong(4, plan.catalogTableId());
            int affected = update.executeUpdate();
            if (affected != 1) {
                throw new SQLException(
                        "Expected one ANONYMIZE_TABLE step, updated " + affected);
            }
        }
    }

    private static long rowsUncheckedLong(ResultSet rows, String column) {
        try {
            return rows.getLong(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int rowsUncheckedInt(ResultSet rows, String column) {
        try {
            return rows.getInt(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String rowsUncheckedString(ResultSet rows, String column) {
        try {
            return rows.getString(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Long rowsUncheckedNullableLong(ResultSet rows, String column) {
        try {
            long value = rows.getLong(column);
            return rows.wasNull() ? null : value;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Integer rowsUncheckedNullableInt(ResultSet rows, String column) {
        try {
            int value = rows.getInt(column);
            return rows.wasNull() ? null : value;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<String> lookupValues(Connection connection, long datasetId)
            throws SQLException {
        String sql = """
                SELECT v.value_text
                  FROM lookup_dataset d
                  JOIN lookup_dataset_value v
                    ON v.lookup_dataset_id = d.lookup_dataset_id AND v.enabled = 'Y'
                 WHERE d.lookup_dataset_id = ? AND d.status = 'PUBLISHED'
                 ORDER BY v.lookup_value_id
                """;
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, datasetId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        if (values.isEmpty()) {
            throw new OracleTemplateException(
                    "Published lookup dataset has no enabled value: " + datasetId);
        }
        return List.copyOf(values);
    }

    static String lookupTemplate(List<String> values, String technologyCode) {
        String hash = switch(technologyCode){
            case "POSTGRESQL" -> "ABS(CAST(HASHTEXT(CAST(${column} AS TEXT)) AS BIGINT))";
            case "SQLSERVER" -> "ABS(CONVERT(bigint,CHECKSUM(CONVERT(nvarchar(max),${column}))))";
            default -> "ORA_HASH(TO_CHAR(${column}))";
        };
        String bucket = "SQLSERVER".equals(technologyCode)
                ? "(" + hash + " % " + values.size() + ")"
                : "MOD(" + hash + ", " + values.size() + ")";
        StringBuilder template = new StringBuilder(
                "CASE WHEN ${column} IS NULL THEN NULL ELSE CASE " + bucket);
        for (int index = 0; index < values.size(); index++) {
            template.append(" WHEN ").append(index)
                    .append("SQLSERVER".equals(technologyCode) ? " THEN N'" : " THEN '")
                    .append(values.get(index).replace("'", "''")).append("'");
        }
        return template.append(" END END").toString();
    }

    static String fitHashToColumn(String template, String algorithmCode,
            String technologyCode, Integer dataLength) {
        if (!("HASH_SHA256".equals(algorithmCode) || "HMAC_SHA256".equals(algorithmCode)
                || "RELATIONSHIP_AWARE_TOKEN".equals(algorithmCode))
                || dataLength == null || dataLength <= 0 || dataLength >= 64) {
            return template;
        }
        return switch (technologyCode) {
            case "POSTGRESQL", "SQLSERVER" -> "LEFT((" + template + ")," + dataLength + ")";
            default -> "SUBSTR((" + template + "),1," + dataLength + ")";
        };
    }

    private static final class MutableTablePlan {
        private final long jobId;
        private final String jobReference;
        private final long catalogTableId;
        private final String technologyCode;
        private final OracleQualifiedTable table;
        private final Map<Long, MutableAssignment> assignments = new LinkedHashMap<>();
        private String tableAlgorithmCode;
        private String filterExpression;

        private MutableTablePlan(
                long jobId,
                String jobReference,
                long catalogTableId,
                String technologyCode,
                OracleQualifiedTable table) {
            this.jobId = jobId;
            this.jobReference = jobReference;
            this.catalogTableId = catalogTableId;
            this.technologyCode = technologyCode;
            this.table = table;
        }

        private RepositoryTablePlan freeze(Connection connection) throws SQLException {
            return new RepositoryTablePlan(
                    jobId,
                    jobReference,
                    catalogTableId,
                    table,
                    tableAlgorithmCode,
                    filterExpression,
                    assignments.values().stream().map(assignment -> {
                        try {
                            return assignment.freeze(connection, technologyCode);
                        } catch (SQLException error) {
                            throw new IllegalStateException(error);
                        }
                    }).toList());
        }

        private void applyColumnFilters() {
            if (assignments.isEmpty()) return;
            List<String> filters = assignments.values().stream()
                    .map(assignment -> assignment.filterExpression).distinct().toList();
            filterExpression = filters.contains("") ? ""
                    : filters.size() == 1 ? filters.get(0)
                    : String.join(" OR ", filters.stream().map(filter -> "(" + filter + ")").toList());
            if (filters.size() > 1) {
                assignments.values().forEach(assignment -> assignment.conditional = true);
            }
        }
    }

    private static final class MutableAssignment {
        private final String columnName;
        private final String algorithmCode;
        private final String implementationType;
        private final String template;
        private final int executionOrder;
        private final Long lookupDatasetId;
        private final Integer dataLength;
        private final List<AlgorithmParameter> definitions = new ArrayList<>();
        private final Map<String, String> values = new LinkedHashMap<>();
        private String filterExpression = "";
        private boolean conditional;

        private MutableAssignment(
                String columnName,
                String algorithmCode,
                String implementationType,
                String template,
                int executionOrder,
                Long lookupDatasetId,
                Integer dataLength) {
            this.columnName = columnName;
            this.algorithmCode = algorithmCode;
            this.implementationType = implementationType;
            this.template = template;
            this.executionOrder = executionOrder;
            this.lookupDatasetId = lookupDatasetId;
            this.dataLength = dataLength;
        }

        private OracleMaskingAssignment freeze(Connection connection, String technologyCode)
                throws SQLException {
            String effectiveTemplate = template;
            List<String> effectiveLookupValues = List.of();
            if ("LOOKUP_MAPPING".equals(implementationType)) {
                if (lookupDatasetId == null) {
                    throw new OracleTemplateException(
                            "LOOKUP_MAPPING rule requires a lookup dataset");
                }
                effectiveLookupValues = lookupValues(connection, lookupDatasetId);
                effectiveTemplate = lookupTemplate(effectiveLookupValues, technologyCode);
            }
            effectiveTemplate = fitHashToColumn(effectiveTemplate, algorithmCode,
                    technologyCode, dataLength);
            if ("SQLSERVER".equals(technologyCode)) {
                for (AlgorithmParameter parameter : definitions) {
                    if (parameter.type() == com.privonym.adapter.oracle.ParameterType.STRING
                            || parameter.type() == com.privonym.adapter.oracle.ParameterType.ENUM) {
                        String token = "${param." + parameter.name() + "}";
                        effectiveTemplate = effectiveTemplate.replace("N" + token, token)
                                .replace(token, "N" + token);
                    }
                }
            }
            if (conditional && !filterExpression.isBlank()) {
                effectiveTemplate = "CASE WHEN (" + filterExpression + ") THEN ("
                        + effectiveTemplate + ") ELSE ${column} END";
            }
            return new OracleMaskingAssignment(
                    columnName,
                    algorithmCode,
                    implementationType,
                    effectiveTemplate,
                    executionOrder,
                    definitions,
                    values,
                    lookupDatasetId,
                    effectiveLookupValues);
        }
    }
}
