package com.privonym.repository.oracle;

/** Regression checks for portable key bounds and row-count validation. */
public final class PortableChunkSmokeCheck {
    private PortableChunkSmokeCheck() { }

    static void verify() {
        require(OracleParallelPlanExecutor.updatesPrimaryKey(
                "\"ID\" = \"ID\" + 1, \"NAME\" = 'X'", "[ID]"),
                "SQL Server double-quoted first key assignment must disable chunking");
        require(OracleParallelPlanExecutor.updatesPrimaryKey(
                "\"NAME\" = 'X', [ID] = 9", "\"ID\""),
                "bracketed later key assignment must disable chunking");
        require(OracleParallelPlanExecutor.updatesPrimaryKey("ID = 9", "[ID]"),
                "unquoted key assignment must disable chunking");
        require(!OracleParallelPlanExecutor.updatesPrimaryKey(
                "\"OTHER_ID\" = 9, \"NAME\" = 'X'", "[ID]"),
                "unrelated column must remain eligible");
        require(!OracleParallelPlanExecutor.updatesPrimaryKey(
                "\"NAME\" = COALESCE(\"ID\", 0)", "[ID]"),
                "reading a key is not changing a key");
        require(OracleParallelPlanExecutor.updatesPrimaryKey("\"a]b\" = 9", "[a]]b]"),
                "escaped bracket identifiers must match");
        require(OracleParallelPlanExecutor.updatesPrimaryKey("[a\"b] = 9", "\"a\"\"b\""),
                "escaped double quotes must match");

        require(OracleParallelPlanExecutor.sqlServerBoundaryExpression("[ID]", "float")
                .equals("CONVERT(nvarchar(max),[ID],3)"), "float boundaries require lossless style 3");
        require(OracleParallelPlanExecutor.sqlServerBoundaryExpression("[ID]", "real")
                .endsWith(",3)"), "real boundaries require lossless style 3");
        require(OracleParallelPlanExecutor.sqlServerBoundaryExpression("[ID]", "money")
                .endsWith(",2)"), "money boundaries must retain four decimal places");
        require(OracleParallelPlanExecutor.sqlServerBoundaryExpression("[ID]", "datetime2(7)")
                .endsWith(",126)"), "date/time boundaries require ISO format with precision");
        require(OracleParallelPlanExecutor.sqlServerBoundaryExpression("[ID]", "datetimeoffset(7)")
                .endsWith(",127)"), "offset boundaries must retain their offset");
        require(OracleParallelPlanExecutor.boundaryParameter("SQLSERVER", "varbinary(16)")
                .equals("CONVERT(varbinary(16),?,2)"), "binary hexadecimal bounds must decode as hexadecimal");
        require(OracleParallelPlanExecutor.sqlServerBoundaryExpression("[ID]", "varbinary(16)")
                .endsWith(",2)"), "binary bounds must serialize as hexadecimal");
        require(OracleParallelPlanExecutor.boundaryParameter("POSTGRESQL", "uuid")
                .equals("CAST(? AS uuid)"), "PostgreSQL UUID comparison must preserve its type");

        OracleParallelPlanExecutor.requireExpectedRows(0, 0);
        OracleParallelPlanExecutor.requireExpectedRows(100_001, 100_001);
        expectMismatch(100_001, 100_000);
        expectMismatch(100_001, 100_002);
        System.out.println("Portable chunk smoke check passed.");
    }

    private static void expectMismatch(long expected, long processed) {
        try {
            OracleParallelPlanExecutor.requireExpectedRows(expected, processed);
        } catch (IllegalStateException mismatch) {
            require(mismatch.getMessage().contains("expected=" + expected)
                    && mismatch.getMessage().contains("processed=" + processed),
                    "mismatch must report both independent counts");
            return;
        }
        throw new IllegalStateException("Missing/extra rows must fail validation");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
