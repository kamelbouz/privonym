package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.GeneratedOracleStatement;
import com.privonym.adapter.oracle.SqlChecksum;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exercises repository loading and SQL generation without a live Oracle database. */
public final class OracleFilteredPlanSmokeCheck {
    private OracleFilteredPlanSmokeCheck() { }

    static void verify() throws Exception {
        for (String technology : List.of("ORACLE", "POSTGRESQL", "SQLSERVER")) {
            String predicate = "STATUS = 'ACTIVE' AND SCORE > 10";
            String filtered = sql(technology, predicate, predicate);
            require(filtered.endsWith("WHERE " + predicate), "shared filter must become WHERE");
            require(!filtered.contains("CASE WHEN"), "shared filter needs no conditional assignment");

            String different = sql(technology, "STATUS = 'ACTIVE'", "SCORE > 10");
            require(different.contains("\"NAME\" = CASE WHEN (STATUS = 'ACTIVE') THEN ('X') ELSE \"NAME\" END"),
                    "first column must retain its own predicate");
            require(different.contains("\"EMAIL\" = CASE WHEN (SCORE > 10) THEN ('X') ELSE \"EMAIL\" END"),
                    "second column must retain its own predicate");
            require(different.endsWith("WHERE (STATUS = 'ACTIVE') OR (SCORE > 10)"),
                    "table predicate must include both populations");

            String mixed = sql(technology, "STATUS = 'ACTIVE'", null);
            require(mixed.contains("ELSE \"NAME\" END"), "filtered column must preserve other rows");
            require(mixed.contains("\"EMAIL\" = 'X'"), "unfiltered column must apply to all rows");
            require(!mixed.contains("\nWHERE "), "unfiltered column must not inherit another filter");

            String unfiltered = sql(technology, null, "  ");
            require(!unfiltered.contains("WHERE ") && !unfiltered.contains("CASE WHEN"),
                    "null and blank filters must preserve existing unfiltered SQL");
            require(sql(technology, "  " + predicate + "  ", predicate).equals(filtered),
                    "outer whitespace must not split identical filters");
            require(!SqlChecksum.sha256(filtered).equals(SqlChecksum.sha256(unfiltered)),
                    "filter must affect the approved checksum");
        }
        for (String template : List.of("${param.replacement}", "N${param.replacement}")) {
            Map<String,Object> row = rule(1L, "NAME", null);
            row.put("implementation_code", template);
            row.put("parameter_name", "replacement"); row.put("parameter_type", "STRING");
            row.put("parameter_required", "Y"); row.put("parameter_value", "中文 O'Brien");
            try (Connection connection = connection(List.of(row))) {
                RepositoryTablePlan plan = new OracleJobPlanRepository().load(
                        connection, "FILTER-JOB", "SQLSERVER").get(0);
                String generated = OraclePlanningAdapter.generateColumnStatement(plan).sql();
                require(generated.contains("N'中文 O''Brien'") && !generated.contains("NN'"),
                        "SQL Server string parameters must preserve Unicode without duplicating N");
            }
        }
        System.out.println("Filtered job plan smoke check passed.");
    }

    private static String sql(String technology, String first, String second) throws Exception {
        List<Map<String,Object>> rows = new ArrayList<>();
        rows.add(rule(1L, "NAME", first));
        rows.add(rule(2L, "EMAIL", second));
        try (Connection connection = connection(rows)) {
            List<RepositoryTablePlan> plans = new OracleJobPlanRepository().load(
                    connection, "FILTER-JOB", technology);
            require(plans.size() == 1, "different filters must retain one saved step per table");
            GeneratedOracleStatement generated = OraclePlanningAdapter.generateColumnStatement(plans.get(0));
            require(generated.sha256().equals(SqlChecksum.sha256(generated.sql())),
                    "checksum must include the final WHERE clause");
            return generated.sql();
        }
    }

    private static Map<String,Object> rule(long id, String column, String filter) {
        Map<String,Object> row = new HashMap<>();
        row.put("job_status", "PLANNING"); row.put("job_id", 42L);
        row.put("job_reference", "FILTER-JOB"); row.put("catalog_table_id", 10L);
        row.put("schema_name", "DEMO"); row.put("table_name", "CUSTOMER");
        row.put("rule_id", id); row.put("column_name", column);
        row.put("algorithm_code", "MASK_STRING"); row.put("implementation_type", "SQL_EXPRESSION");
        row.put("implementation_code", "'X'"); row.put("execution_order", (int) id);
        row.put("filter_expression", filter);
        return row;
    }

    private static Connection connection(List<Map<String,Object>> rows) {
        return Connection.class.cast(Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("close")) return null;
                    if (method.getName().equals("prepareStatement")) {
                        boolean structural = ((String) arguments[0]).contains("JOIN table_masking_rule");
                        return statement(structural ? List.of() : rows);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    private static PreparedStatement statement(List<Map<String,Object>> rows) {
        return PreparedStatement.class.cast(Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("executeQuery")) return resultSet(rows);
                    if (method.getName().equals("setString") || method.getName().equals("close")) return null;
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    private static ResultSet resultSet(List<Map<String,Object>> rows) {
        int[] position = {-1};
        boolean[] wasNull = {false};
        return ResultSet.class.cast(Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                    String name = method.getName();
                    if (name.equals("close")) return null;
                    if (name.equals("next")) return ++position[0] < rows.size();
                    if (name.equals("wasNull")) return wasNull[0];
                    Object value = rows.get(position[0]).get((String) arguments[0]);
                    wasNull[0] = value == null;
                    if (name.equals("getString")) return value == null ? null : value.toString();
                    if (name.equals("getLong")) return value == null ? 0L : ((Number) value).longValue();
                    if (name.equals("getInt")) return value == null ? 0 : ((Number) value).intValue();
                    throw new UnsupportedOperationException(name);
                }));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
