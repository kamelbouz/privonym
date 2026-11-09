package com.privonym.repository.oracle;

import java.util.List;

/** Prevents Oracle-only functions from leaking into SQL Server lookup plans. */
public final class OracleSqlServerPlanSmokeCheck {
    private OracleSqlServerPlanSmokeCheck() { }

    public static void main(String[] args) throws Exception {
        OracleFilteredPlanSmokeCheck.verify();
        PortableChunkSmokeCheck.verify();
        CancellableQueriesSmokeCheck.verify();
        NativeSqlExpressionPlanSmokeCheck.verify();
        AuditRemediationSmokeCheck.verify();
        Lot3StartupSmokeCheck.verify();
        Lot2RecoverySmokeCheck.verify();
        SavedPlanResumeSmokeCheck.verify();
        WorkerShutdownSmokeCheck.verify();
        Lot2DirectRecoverySmokeCheck.verify();
        CommitRecoverySmokeCheck.verify();
        ChunkResumeSmokeCheck.verify();
        FailureReportSmokeCheck.verify();
        TargetValidationSmokeCheck.verify();
        if (!OracleJobPlanRepository.lookupTemplate(List.of("中文 O'Brien"), "SQLSERVER")
                .contains("THEN N'中文 O''Brien'")) {
            throw new IllegalStateException("SQL Server lookup values must preserve Unicode");
        }
        String sqlServer = OracleJobPlanRepository.lookupTemplate(
                List.of("ALICE", "BOB", "CAROL"), "SQLSERVER");
        if (sqlServer.toUpperCase().contains("MOD(")) {
            throw new IllegalStateException(
                    "SQL Server lookup contains unsupported MOD(): " + sqlServer);
        }
        if (!sqlServer.contains(" % 3") || !sqlServer.contains("CHECKSUM(")) {
            throw new IllegalStateException(
                    "SQL Server lookup does not use the modulo operator: " + sqlServer);
        }

        String oracle = OracleJobPlanRepository.lookupTemplate(
                List.of("ALICE", "BOB"), "ORACLE");
        if (!oracle.contains("MOD(ORA_HASH(")) {
            throw new IllegalStateException("Oracle lookup lost MOD(): " + oracle);
        }

        String postgresql = OracleJobPlanRepository.lookupTemplate(
                List.of("ALICE", "BOB"), "POSTGRESQL");
        if (!postgresql.contains("MOD(ABS(CAST(HASHTEXT(")) {
            throw new IllegalStateException("PostgreSQL lookup lost MOD(): " + postgresql);
        }
        if (!"POSTGRESQL".equals(OracleParallelPlanExecutor.technologyFromJdbcUrl(
                "jdbc:postgresql://localhost:5432/demo"))) {
            throw new IllegalStateException("PostgreSQL target URL was not recognized");
        }
        if (!"SQLSERVER".equals(OracleParallelPlanExecutor.technologyFromJdbcUrl(
                "jdbc:sqlserver://localhost:1433;databaseName=demo"))) {
            throw new IllegalStateException("SQL Server target URL was not recognized");
        }

        String hash = "CASE WHEN ${column} IS NULL THEN NULL ELSE HASH_VALUE END";
        String sqlServerHash = OracleJobPlanRepository.fitHashToColumn(
                hash, "HASH_SHA256", "SQLSERVER", 40);
        String postgresqlHash = OracleJobPlanRepository.fitHashToColumn(
                hash, "HASH_SHA256", "POSTGRESQL", 40);
        String oracleHash = OracleJobPlanRepository.fitHashToColumn(
                hash, "HASH_SHA256", "ORACLE", 40);
        if (!sqlServerHash.startsWith("LEFT((") || !sqlServerHash.endsWith("),40)"))
            throw new IllegalStateException("SQL Server hash was not length-fitted: " + sqlServerHash);
        if (!postgresqlHash.startsWith("LEFT((") || !postgresqlHash.endsWith("),40)"))
            throw new IllegalStateException("PostgreSQL hash was not length-fitted: " + postgresqlHash);
        if (!oracleHash.startsWith("SUBSTR((") || !oracleHash.endsWith("),1,40)"))
            throw new IllegalStateException("Oracle hash was not length-fitted: " + oracleHash);
        if (!OracleJobPlanRepository.fitHashToColumn(
                hash, "HASH_SHA256", "SQLSERVER", 64).equals(hash))
            throw new IllegalStateException("A 64-character column should retain the full hash");
        String relationshipToken = OracleJobPlanRepository.fitHashToColumn(
                hash, "RELATIONSHIP_AWARE_TOKEN", "ORACLE", 32);
        if (!relationshipToken.startsWith("SUBSTR((") || !relationshipToken.endsWith("),1,32)"))
            throw new IllegalStateException(
                    "Relationship token was not length-fitted: " + relationshipToken);

        requireCompatible("MASK_STRING", "MASKING", "SQL_EXPRESSION", "VARCHAR2", true);
        requireCompatible("MASK_STRING", "MASKING", "SQL_EXPRESSION", "NUMBER", false);
        requireCompatible("PERTURB_NUMBER_PERCENT", "PERTURBATION", "SQL_EXPRESSION",
                "numeric(18,2)", true);
        requireCompatible("PERTURB_NUMBER_PERCENT", "PERTURBATION", "SQL_EXPRESSION",
                "nvarchar(50)", false);
        requireCompatible("DATE_SHIFT_DETERMINISTIC", "GENERALIZATION", "SQL_EXPRESSION",
                "timestamp without time zone", true);
        requireCompatible("RELATIONSHIP_AWARE_TOKEN", "TOKENIZATION", "SQL_EXPRESSION",
                "VARCHAR2", true);
        requireCompatible("RELATIONSHIP_AWARE_TOKEN", "TOKENIZATION", "SQL_EXPRESSION",
                "NUMBER", false);
        requireCompatible("GENERALIZE_DATE_YEAR", "GENERALIZATION", "SQL_EXPRESSION",
                "VARCHAR2", false);
        requireCompatible("LOOKUP_FIRST_NAME", "SUBSTITUTION", "LOOKUP_MAPPING",
                "NUMBER", false);
        requireCompatible("NULLIFY", "SUPPRESSION", "SQL_EXPRESSION", "BLOB", true);
        requireCompatible("IBAN_GENERATOR", "SYNTHETIC", "DATABASE_FUNCTION",
                "VARCHAR2", true);
        requireCompatible("IBAN_GENERATOR", "SYNTHETIC", "DATABASE_FUNCTION",
                "NUMBER", false);
        requireCompatible("PERSISTENT_MAPPING", "TOKENIZATION", "LOOKUP_MAPPING",
                "nvarchar(100)", true);
        requireCompatible("GEOGRAPHIC_COHERENT_ADDRESS", "SYNTHETIC", "LOOKUP_MAPPING",
                "DATE", false);
        requireCompatible("JSON_PATH_MASK", "MASKING", "SQL_EXPRESSION",
                "JSONB", "POSTGRESQL", true);
        requireCompatible("JSON_PATH_MASK", "MASKING", "SQL_EXPRESSION",
                "TEXT", "POSTGRESQL", false);
        requireCompatible("JSON_PATH_MASK", "MASKING", "SQL_EXPRESSION",
                "NVARCHAR(MAX)", "SQLSERVER", true);
        System.out.println("SQL Server lookup plan smoke check passed.");
    }

    private static void requireCompatible(String code, String category, String implementation,
            String dataType, boolean expected) {
        boolean actual = OracleRulesetAdministrationAdapter.isAlgorithmCompatible(
                code, category, implementation, dataType);
        if (actual != expected) throw new IllegalStateException("Compatibility mismatch for "
                + code + " on " + dataType + ": expected " + expected + " but got " + actual);
    }

    private static void requireCompatible(String code, String category, String implementation,
            String dataType, String technology, boolean expected) {
        boolean actual = OracleRulesetAdministrationAdapter.isAlgorithmCompatible(
                code, category, implementation, dataType, technology);
        if (actual != expected) throw new IllegalStateException("Compatibility mismatch for "
                + code + " on " + technology + " " + dataType + ": expected " + expected
                + " but got " + actual);
    }
}
