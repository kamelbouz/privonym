package com.privonym.repository.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/** Optional Oracle integration check: SELECT only, no migration or table updates. */
public final class TextMaskingOracleCheck {
    private TextMaskingOracleCheck() { }

    public static Map<String,String> profiles(Path migration) throws Exception {
        String source = Files.readString(migration);
        Map<String,String> patterns = new LinkedHashMap<>();
        var matcher = Pattern.compile("profile\\('([^']+)'\\s*,\\s*'[^']*'\\s*,\\s*q'~(.*?)~'\\);",
                Pattern.DOTALL).matcher(source);
        while (matcher.find()) patterns.put(matcher.group(1), matcher.group(2));
        if (patterns.size() != 4 || source.contains("REGEXP_REPLACE(TO_CHAR(${column})")) {
            throw new IllegalStateException("Expected four profiles and a CLOB-preserving template");
        }
        if (!source.contains("created_by='V2_022'") || source.contains("UPDATE masking_rule")) {
            throw new IllegalStateException("Migration must preserve existing rule version links");
        }
        return patterns;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "Expected local job properties and V2_022 migration path");
        Map<String,String> patterns = profiles(Path.of(args[1]));
        Properties config = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) { config.load(input); }
        String url = config.getProperty("repository.url");
        if (url == null || url.isBlank()) url = "jdbc:oracle:thin:@//"
                + config.getProperty("repository.host") + ":" + config.getProperty("repository.port", "1521")
                + "/" + config.getProperty("repository.service");
        Properties credentials = new Properties();
        credentials.setProperty("user", config.getProperty("repository.user"));
        credentials.setProperty("password", config.getProperty("repository.password"));
        credentials.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        credentials.setProperty("oracle.jdbc.ReadTimeout", "15000");
        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) { statement.execute("SET TRANSACTION READ ONLY"); }
            String migration = Files.readString(Path.of(args[1]));
            String block = migration.substring(migration.indexOf("DECLARE"), migration.lastIndexOf("\n/"));
            // Parse/compile the anonymous block; never execute its catalog updates.
            try (var statement = connection.prepareCall("DECLARE c INTEGER; BEGIN "
                    + "c:=DBMS_SQL.OPEN_CURSOR; BEGIN DBMS_SQL.PARSE(c,?,DBMS_SQL.NATIVE); "
                    + "EXCEPTION WHEN OTHERS THEN DBMS_SQL.CLOSE_CURSOR(c); RAISE; END; "
                    + "DBMS_SQL.CLOSE_CURSOR(c); END;")) {
                statement.setString(1, block); statement.execute();
            }
            check(connection, patterns.get("TEXT_REDACT_EMAIL"),
                    "Élodie : alice@example.com, bob@test.ch.", "Élodie : ANONYMIZED, ANONYMIZED.");
            check(connection, patterns.get("TEXT_REDACT_PHONE_FR"),
                    "+33 6 12 34 56 78 / 06.12.34.56.78", "ANONYMIZED / ANONYMIZED");
            check(connection, patterns.get("TEXT_REDACT_PHONE_CH"),
                    "+41 79 123 45 67 / 079 123 45 67", "ANONYMIZED / ANONYMIZED");
            check(connection, patterns.get("TEXT_REDACT_IBAN_FR_CH"),
                    "CH93 0076 2011 6238 5295 7 / FR76 3000 6000 0112 3456 7890 189",
                    "ANONYMIZED / ANONYMIZED");
            String padding = "Texte conservé. ".repeat(180);
            List<String> pieces = new ArrayList<>();
            pieces.add("Début alice@example.com. ");
            for (int index = 0; index < 25; index++) pieces.add(padding);
            pieces.add(" Fin bob@test.ch !");
            String original = String.join("", pieces);
            String replacement = "masqué d'Élodie \\1";
            String expected = original.replace("alice@example.com", replacement).replace("bob@test.ch", replacement);
            String actual = replace(connection, pieces, patterns.get("TEXT_REDACT_EMAIL"), replacement);
            if (original.length() <= 65_536 || !expected.equals(actual)) {
                throw new IllegalStateException("Long CLOB/end-of-document/literal replacement check failed");
            }
            var generic = Pattern.compile("implementation\\('ORACLE',\\s*q'~(.*?)~'\\)",
                    Pattern.DOTALL).matcher(Files.readString(Path.of(args[1])));
            if (!generic.find()) throw new IllegalStateException("Missing REGEX_MASK CLOB template");
            String expression = generic.group(1).replace("${column}", "\"BODY\"")
                    .replace("${param.pattern}", "?").replace("${param.replacement}", "?");
            String clobSource = String.join(" || ", java.util.Collections.nCopies(pieces.size(), "TO_CLOB(?)"));
            try (var statement = connection.prepareStatement("SELECT " + expression
                    + " FROM (SELECT " + clobSource + " AS \"BODY\" FROM dual)")) {
                statement.setQueryTimeout(15);
                statement.setString(1, patterns.get("TEXT_REDACT_EMAIL"));
                statement.setString(2, "[EMAIL]");
                int index = 3;
                for (String piece : pieces) statement.setString(index++, piece);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    if (!original.replace("alice@example.com", "[EMAIL]")
                            .replace("bob@test.ch", "[EMAIL]").equals(rows.getString(1))) {
                        throw new IllegalStateException("REGEX_MASK template lost CLOB content");
                    }
                }
            }
            if (!"Texte sans donnée sensible.".equals(replace(connection,
                    List.of("Texte sans donnée sensible."), patterns.get("TEXT_REDACT_EMAIL"), "X"))) {
                throw new IllegalStateException("Unmatched text changed");
            }
            try (var statement = connection.prepareStatement(
                    "SELECT REGEXP_REPLACE(TO_CLOB(CAST(NULL AS VARCHAR2(1))),?,?) FROM dual")) {
                statement.setString(1, patterns.get("TEXT_REDACT_EMAIL")); statement.setString(2, "X");
                try (var rows = statement.executeQuery()) {
                    rows.next(); if (rows.getString(1) != null) throw new IllegalStateException("NULL changed");
                }
            }
            connection.rollback();
            System.out.println("Migration compiled without execution. Oracle REGEX_MASK and text profiles: four formats, >65K CLOB, accents, repeated/end matches, literal replacement, no-match and NULL passed (read-only).");
        }
    }

    private static void check(Connection connection,String pattern,String input,String expected) throws Exception {
        if (!expected.equals(replace(connection, List.of(input), pattern, "ANONYMIZED"))) {
            throw new IllegalStateException("Profile replacement failed");
        }
    }

    private static String replace(Connection connection,List<String> pieces,String pattern,String replacement)
            throws Exception {
        String source = String.join(" || ", java.util.Collections.nCopies(pieces.size(), "TO_CLOB(?)"));
        try (var statement = connection.prepareStatement("SELECT REGEXP_REPLACE(" + source
                + ",?,REPLACE(?,CHR(92),CHR(92)||CHR(92)),1,0,'i') FROM dual")) {
            statement.setQueryTimeout(15);
            int index = 1;
            for (String piece : pieces) statement.setString(index++, piece);
            statement.setString(index++, pattern); statement.setString(index, replacement);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getString(1); }
        }
    }
}
