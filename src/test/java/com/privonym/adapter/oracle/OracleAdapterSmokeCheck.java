package com.privonym.adapter.oracle;

import com.privonym.repository.oracle.OracleConnectionDetails;
import java.util.List;
import java.util.Map;

/** Dependency-free smoke check for environments where Maven cannot resolve JUnit. */
public final class OracleAdapterSmokeCheck {
    private OracleAdapterSmokeCheck() {
    }

    public static void main(String[] args) {
        OracleTemplateRenderer renderer = new OracleTemplateRenderer();
        String sql = renderer.render(
                "REGEXP_REPLACE(${column}, '[[:alnum:]]', ${param.mask_character})",
                "first_name",
                new OracleQualifiedTable("demo_app", "customer"),
                List.of(AlgorithmParameter.string("mask_character", false, "X", 1)),
                Map.of());

        String expected = "REGEXP_REPLACE(\"first_name\", '[[:alnum:]]', 'X')";
        if (!expected.equals(sql)) {
            throw new AssertionError("Unexpected SQL: " + sql);
        }

        String fixedValueMask = renderer.render(
                "CASE WHEN ${column} IS NULL THEN NULL ELSE ${param.replacement_value} END",
                "status", new OracleQualifiedTable("demo_app", "customer"),
                List.of(AlgorithmParameter.string("replacement_value", true, null, 4000)),
                Map.of("replacement_value", "MASKED O'Reilly"));
        if (!fixedValueMask.equals(
                "CASE WHEN \"status\" IS NULL THEN NULL ELSE 'MASKED O''Reilly' END")) {
            throw new AssertionError(
                    "MASK_STRING replacement value was not rendered safely: " + fixedValueMask);
        }

        try {
            OracleIdentifier.quote("CUSTOMER; DROP TABLE CUSTOMER");
            throw new AssertionError("Unsafe identifier was accepted");
        } catch (OracleTemplateException expectedException) {
            // Expected rejection.
        }

        GeneratedOracleStatement statement = new OracleUpdateGenerator().generate(
                new OracleQualifiedTable("demo_app", "customer"),
                List.of(
                        new OracleMaskingAssignment(
                                "first_name",
                                "MASK_STRING",
                                "SQL_EXPRESSION",
                                "REGEXP_REPLACE(${column}, '[[:alnum:]]', ${param.mask_character})",
                                10,
                                List.of(AlgorithmParameter.string(
                                        "mask_character", false, "X", 1)),
                                Map.of()),
                        new OracleMaskingAssignment(
                                "birth_date",
                                "GENERALIZE_DATE_YEAR",
                                "SQL_EXPRESSION",
                                "ADD_MONTHS(TRUNC(${column}, 'YEAR'), ${param.month_number} - 1)"
                                        + " + (${param.day_number} - 1)",
                                20,
                                List.of(
                                        AlgorithmParameter.integer(
                                                "month_number", false, "6", 1, 12),
                                        AlgorithmParameter.integer(
                                                "day_number", false, "15", 1, 28)),
                                Map.of())));

        if (!statement.sql().startsWith("UPDATE \"demo_app\".\"customer\"")) {
            throw new AssertionError("Unexpected generated UPDATE: " + statement.sql());
        }
        if (!statement.sha256().matches("[0-9a-f]{64}")) {
            throw new AssertionError("Invalid statement checksum");
        }

        GeneratedOracleStatement lookup = new OracleUpdateGenerator().generate(
                new OracleQualifiedTable("formation", "emp"),
                List.of(new OracleMaskingAssignment(
                        "ename", "LOOKUP_FROM_DATASET", "LOOKUP_MAPPING",
                        "CASE MOD(ORA_HASH(TO_CHAR(${column})), 2)"
                                + " WHEN 0 THEN 'ALAIN' WHEN 1 THEN 'SOPHIE' END",
                        10, List.of(), Map.of())));
        if (!lookup.sql().contains("WHEN 0 THEN 'ALAIN'")) {
            throw new AssertionError("Lookup values were not embedded in generated SQL");
        }

        String hmac = renderer.render(
                "DBMS_CRYPTO.MAC(UTL_I18N.STRING_TO_RAW(TO_CHAR(${column}),'AL32UTF8'),"
                        + "DBMS_CRYPTO.HMAC_SH256,UTL_I18N.STRING_TO_RAW(${param.key_material},'AL32UTF8'))",
                "customer_id", new OracleQualifiedTable("demo_app", "customer"),
                List.of(AlgorithmParameter.string("key_material", true, null, 512)),
                Map.of("key_material", "bank-key"));
        if (!hmac.contains("'bank-key'") || !hmac.contains("\"customer_id\"")) {
            throw new AssertionError("HMAC template was not rendered safely: " + hmac);
        }

        String dateShift = renderer.render(
                "CASE WHEN ${column} IS NULL THEN NULL ELSE ${column}+${param.shift_days} END",
                "birth_date", new OracleQualifiedTable("demo_app", "customer"),
                List.of(AlgorithmParameter.integer("shift_days", false, "30", -36500, 36500)),
                Map.of("shift_days", "-45"));
        if (!dateShift.contains("\"birth_date\"+-45")) {
            throw new AssertionError("Date-shift template was not rendered: " + dateShift);
        }

        String conditional = renderer.render(
                "CASE WHEN ${column}=${param.condition_value} THEN ${param.replacement_value} ELSE ${column} END",
                "status", new OracleQualifiedTable("demo_app", "customer"),
                List.of(AlgorithmParameter.string("condition_value", true, null, 1000),
                        AlgorithmParameter.string("replacement_value", false, "MASKED", 1000)),
                Map.of("condition_value", "O'Reilly"));
        if (!conditional.contains("'O''Reilly'") || !conditional.contains("'MASKED'")) {
            throw new AssertionError("Conditional mask literals were not escaped: " + conditional);
        }

        String sqlServerUrl = new OracleConnectionDetails(
                "SQLSERVER", "sqlserver.local", 1433, "", "BANK_TEST", "privonym")
                .jdbcUrl();
        if (!sqlServerUrl.startsWith("jdbc:sqlserver://sqlserver.local:1433;databaseName=BANK_TEST")
                || !sqlServerUrl.contains("encrypt=true")) {
            throw new AssertionError("Invalid SQL Server JDBC URL: " + sqlServerUrl);
        }

        System.out.println("Oracle Adapter smoke check passed.");
        System.out.println(sql);
        System.out.println(statement.sql());
        System.out.println("SHA-256: " + statement.sha256());
    }
}
