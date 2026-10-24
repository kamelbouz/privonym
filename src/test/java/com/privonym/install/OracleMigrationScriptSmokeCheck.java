package com.privonym.install;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Verifies that every bundled Oracle migration can be split by the production runner. */
public final class OracleMigrationScriptSmokeCheck {
    private OracleMigrationScriptSmokeCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Expected migration directory, baseline SQL and metadata SQL");
        }
        Path directory = Path.of(args[0]);
        verifyIdentitySeeds(Path.of(args[1]), Path.of(args[2]));
        List<Path> migrations;
        try (Stream<Path> files = Files.list(directory)) {
            migrations = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("_oracle.sql"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
        if (migrations.isEmpty()) {
            throw new IllegalStateException("No Oracle migration found in " + directory);
        }
        verifyPlSqlBlockParsing();
        verifyMixedOracleScriptParsing();
        verifyMultilineUpdateAndMergeParsing();
        verifyMaskStringMigration(directory.resolve(
                "V2_016_mask_string_replacement_value_oracle.sql"));
        com.privonym.repository.oracle.TextMaskingOracleCheck.profiles(
                directory.resolve("V2_022_clob_text_masking_profiles_oracle.sql"));
        int statements = 0;
        for (Path migration : migrations) {
            List<String> parsed = OracleRepositoryInstaller.statements(
                    Files.readString(migration, StandardCharsets.UTF_8));
            if (parsed.isEmpty()) {
                throw new IllegalStateException("Migration contains no executable statement: " + migration);
            }
            for (String statement : parsed) {
                if (statement.equals("/")) {
                    throw new IllegalStateException("SQL*Plus block separator leaked into a statement: " + migration);
                }
            }
            statements += parsed.size();
        }
        System.out.printf("Oracle migration script smoke check passed: %d files, %d statements.%n",
                migrations.size(), statements);
    }

    private static void verifyPlSqlBlockParsing() {
        String sample = """
                SET SERVEROUTPUT ON
                DECLARE
                    v_value NUMBER := 0;
                BEGIN
                    v_value := v_value + 1;
                    IF v_value = 1 THEN
                        DBMS_OUTPUT.PUT_LINE('PL/SQL; semicolons are internal');
                    END IF;
                END;
                /
                CREATE TABLE parser_check (id NUMBER);
                """;
        List<String> parsed = OracleRepositoryInstaller.statements(sample);
        if (parsed.size() != 2) throw new IllegalStateException(
                "Expected one complete PL/SQL block and one SQL statement, got " + parsed.size());
        if (!parsed.get(0).endsWith("END;")) throw new IllegalStateException(
                "The PL/SQL final semicolon was removed");
        if (!parsed.get(0).contains("v_value := v_value + 1;")) throw new IllegalStateException(
                "An internal PL/SQL semicolon incorrectly terminated the block");
        if (!parsed.get(1).equals("CREATE TABLE parser_check (id NUMBER)"))
            throw new IllegalStateException("The SQL statement following the PL/SQL block was corrupted");
    }

    private static void verifyMixedOracleScriptParsing() {
        String sample = """
                /* installation header; this semicolon is a comment */
                CREATE TABLE parser_mixed (id NUMBER, text_value VARCHAR2(100));

                CREATE OR REPLACE
                EDITIONABLE
                TRIGGER parser_mixed_bir
                BEFORE INSERT ON parser_mixed
                FOR EACH ROW
                BEGIN
                    :NEW.text_value := q'[PL/SQL; value]';
                    IF :NEW.id IS NULL THEN
                        :NEW.id := 1;
                    END IF;
                END;
                /

                INSERT INTO parser_mixed(id, text_value)
                VALUES (2, q'{SQL; value}'); -- trailing SQL comment; ignored
                COMMIT;
                /
                """;
        List<String> parsed = OracleRepositoryInstaller.statements(sample);
        if (parsed.size() != 4) throw new IllegalStateException(
                "Expected SQL, trigger, INSERT and COMMIT, got " + parsed.size() + ": " + parsed);
        if (!parsed.get(0).startsWith("/* installation header")
                || !parsed.get(0).contains("CREATE TABLE parser_mixed"))
            throw new IllegalStateException("Leading block comment corrupted the first SQL statement");
        if (!parsed.get(1).contains("TRIGGER parser_mixed_bir")
                || !parsed.get(1).endsWith("END;"))
            throw new IllegalStateException("Multiline CREATE OR REPLACE trigger was split");
        if (!parsed.get(2).contains("q'{SQL; value}'"))
            throw new IllegalStateException("Oracle alternative quote was split at its semicolon");
        if (!parsed.get(3).endsWith("COMMIT"))
            throw new IllegalStateException("SQL after the PL/SQL block was lost");
    }

    private static void verifyMultilineUpdateAndMergeParsing() {
        String sample = """
                SET DEFINE OFF
                UPDATE algorithm
                   SET status = 'ACTIVE',
                       description = 'Updated'
                 WHERE code = 'MASK_STRING';

                MERGE INTO algorithm target
                USING (SELECT 'MASK_STRING' code FROM dual) source
                   ON (target.code = source.code)
                WHEN MATCHED THEN
                  UPDATE
                     SET target.status = 'ACTIVE'
                WHEN NOT MATCHED THEN
                  INSERT (code,name,category,status)
                  VALUES ('MASK_STRING','Mask','MASKING','ACTIVE');
                """;
        List<String> parsed = OracleRepositoryInstaller.statements(sample);
        if (parsed.size() != 2) throw new IllegalStateException(
                "Expected one UPDATE and one MERGE, got " + parsed.size() + ": " + parsed);
        if (!parsed.get(0).contains("SET status = 'ACTIVE'"))
            throw new IllegalStateException("The multiline UPDATE SET clause was discarded");
        if (!parsed.get(1).contains("SET target.status = 'ACTIVE'"))
            throw new IllegalStateException("The multiline MERGE SET clause was discarded");
        if (parsed.stream().anyMatch(statement -> statement.startsWith("SET DEFINE")))
            throw new IllegalStateException("A SQL*Plus SET command leaked into JDBC SQL");
    }

    private static void verifyMaskStringMigration(Path migration) throws Exception {
        String sql = Files.readString(migration, StandardCharsets.UTF_8);
        if (!sql.contains("'replacement_value','STRING','Y'")
                || !sql.contains("${param.replacement_value}")
                || !sql.contains("upsert_implementation('ORACLE'")
                || !sql.contains("upsert_implementation('POSTGRESQL'")
                || !sql.contains("upsert_implementation('SQLSERVER'")) {
            throw new IllegalStateException(
                    "MASK_STRING must require a replacement value for all target technologies");
        }
    }

    private static void verifyIdentitySeeds(Path baselinePath, Path metadataPath)
            throws Exception {
        String baseline = Files.readString(baselinePath, StandardCharsets.UTF_8);
        String metadata = Files.readString(metadataPath, StandardCharsets.UTF_8);
        Pattern identityPattern = Pattern.compile(
                "(?is)CREATE\\s+TABLE\\s+\"([A-Z0-9_]+)\"\\s*\\(\\s*"
                + "\"([A-Z0-9_]+)\"\\s+NUMBER\\s+GENERATED.*?START\\s+WITH\\s+(\\d+)");
        Map<String, Long> starts = new HashMap<>();
        Matcher identities = identityPattern.matcher(baseline);
        while (identities.find()) starts.put(identities.group(1),
                Long.parseLong(identities.group(3)));

        Pattern seedPattern = Pattern.compile(
                "(?im)^INSERT\\s+INTO\\s+([A-Z0-9_]+)\\s*\\(\\s*([A-Z0-9_]+)[^)]*\\)"
                + "\\s*VALUES\\s*\\(\\s*(\\d+)");
        Matcher seeds = seedPattern.matcher(metadata);
        int checked = 0;
        while (seeds.find()) {
            Long start = starts.get(seeds.group(1).toUpperCase());
            if (start == null) continue;
            long explicitId = Long.parseLong(seeds.group(3));
            if (start <= explicitId) throw new IllegalStateException(
                    "Identity for " + seeds.group(1) + " starts at " + start
                    + " but metadata explicitly seeds key " + explicitId);
            checked++;
        }
        if (checked == 0) throw new IllegalStateException(
                "No explicit metadata key was checked against an identity generator");
    }
}
