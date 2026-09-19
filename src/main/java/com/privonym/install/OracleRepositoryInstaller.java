package com.privonym.install;

import com.privonym.repository.oracle.OracleDiscoveryAdapter;
import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Installs the versioned Privonym model in an empty Oracle repository schema. */
public final class OracleRepositoryInstaller {
    private OracleRepositoryInstaller() { }

    public static void main(String[] args) {
        if (args.length < 4 || args.length > 5
                || (args.length == 5 && !"--reset".equals(args[4]))) {
            System.err.println("Usage: OracleRepositoryInstaller <properties> <baseline.sql> <metadata.sql> <migration-directory> [--reset]");
            System.exit(2);
        }
        boolean reset = args.length == 5;
        char[] password = new char[0];
        try {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(Path.of(args[0]))) { properties.load(input); }
            String url = required(properties, "repository.url");
            String user = required(properties, "repository.user");
            Console console = System.console();
            if (console == null) throw new IllegalStateException("An interactive console is required to enter the repository password");
            password = console.readPassword("Repository password for %s: ", user);
            if (password == null || password.length == 0) throw new IllegalArgumentException("Repository password is required");
            Path configurationPath = Path.of(args[0]).toAbsolutePath();
            try (Connection connection = DriverManager.getConnection(url, user, new String(password))) {
                connection.setAutoCommit(false);
                if (reset) resetRepository(connection, user);
                if (tableExists(connection, "ALGORITHM")) {
                    throw new IllegalStateException("The repository schema is not empty; automatic baseline installation was refused");
                }
                executeScript(connection, Path.of(args[1]));
                executeScript(connection, Path.of(args[2]));
                int[] migrations = OracleRepositoryUpgrader.applyAll(connection, Path.of(args[3]), user);
                verifyModel(connection);
                System.out.printf(Locale.ROOT, "Repository migrations installed: %d applied, %d already present.%n",
                        migrations[0], migrations[1]);
            }
            String previousSecretKey = System.getProperty("privonym.secret.key.file");
            Path secretKey = configurationPath.getParent().resolve("privonym-secret.key");
            try {
                System.setProperty("privonym.secret.key.file", secretKey.toString());
                try (OracleDiscoveryAdapter ignored =
                        new OracleDiscoveryAdapter(url, user, password)) {
                    // Construction provisions the portable algorithm implementations and
                    // the complete built-in lookup catalogues idempotently.
                    if (!ignored.listConnections().isEmpty()) {
                        throw new SQLException(
                                "Fresh repository unexpectedly contains target connections");
                    }
                }
            } finally {
                if (previousSecretKey == null) System.clearProperty("privonym.secret.key.file");
                else System.setProperty("privonym.secret.key.file", previousSecretKey);
            }
            try (Connection connection = DriverManager.getConnection(url, user, new String(password))) {
                verifyModel(connection);
                verifySystemMetadata(connection);
                verifyPristineUserMetadata(connection);
            }
            System.out.println("Privonym repository model and system metadata installed successfully.");
        } catch (Exception error) {
            System.err.println("ERROR: Unable to initialize Privonym repository: " + error.getMessage());
            System.exit(1);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void executeScript(Connection connection, Path path) throws IOException, SQLException {
        if (!Files.isRegularFile(path)) throw new IOException("SQL installation file not found: " + path);
        List<String> parsed = statements(Files.readString(path, StandardCharsets.UTF_8));
        System.out.printf(Locale.ROOT, "Executing %s: %d statement(s).%n",
                path.getFileName(), parsed.size());
        for (int index = 0; index < parsed.size(); index++) {
            String sql = parsed.get(index);
            try (Statement statement = connection.createStatement()) { statement.execute(sql); }
            catch (SQLException error) {
                throw new SQLException(path.getFileName() + ", statement " + (index + 1)
                        + "/" + parsed.size() + ": " + abbreviated(sql) + ": "
                        + error.getMessage(), error);
            }
        }
    }

    static List<String> statements(String script) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean plsql = false;
        for (String raw : script.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = raw.strip();
            String upper = line.toUpperCase(Locale.ROOT);
            if (line.isEmpty() || line.startsWith("--")) continue;
            if (current.toString().isBlank()) current.setLength(0);
            if (isSqlPlusCommand(upper) && containsOnlyComments(current)) {
                current.setLength(0);
                continue;
            }
            if (plsql && line.equals("/")) {
                add(result, current, true);
                plsql = false;
                continue;
            }
            if (!plsql && line.equals("/")) {
                // SQL*Plus also emits '/' after stored program units. JDBC must never
                // receive that client-side delimiter as an executable statement.
                if (current.length() > 0) add(result, current, false);
                continue;
            }
            current.append(raw).append('\n');
            if (!plsql) plsql = startsPlSql(current.toString());
            if (!plsql) extractSqlStatements(result, current);
        }
        add(result, current, plsql);
        return result;
    }

    private static boolean isSqlPlusCommand(String upper) {
        return upper.startsWith("SET ") || upper.startsWith("WHENEVER ")
                || upper.startsWith("PROMPT ") || upper.startsWith("SPOOL ")
                || upper.equals("EXIT") || upper.startsWith("EXIT ")
                || upper.startsWith("REM ") || upper.startsWith("REMARK ")
                || upper.startsWith("DEFINE ") || upper.startsWith("UNDEFINE ");
    }

    private static boolean containsOnlyComments(StringBuilder value) {
        String remaining = value.toString();
        String previous;
        do {
            previous = remaining;
            remaining = remaining.replaceFirst(
                    "(?s)^(?:\\s+|--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)*", "");
        } while (!remaining.equals(previous));
        return remaining.isBlank();
    }

    private static boolean startsPlSql(String text) {
        String upper = text.replaceFirst(
                "(?s)^(?:\\s+|--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)*", "")
                .toUpperCase(Locale.ROOT);
        if (upper.matches("(?s)^(?:<<[^>]+>>\\s*)*(DECLARE|BEGIN)\\b.*")) return true;
        return upper.matches("(?s)^CREATE\\s+(?:OR\\s+REPLACE\\s+)?"
                + "(?:(?:EDITIONABLE|NONEDITIONABLE)\\s+)?"
                + "(TRIGGER|PROCEDURE|FUNCTION|PACKAGE(?:\\s+BODY)?|TYPE(?:\\s+BODY)?)\\b.*");
    }

    private static void extractSqlStatements(List<String> result, StringBuilder current) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean blockComment = false;
        for (int i = 0; i < current.length(); i++) {
            char value = current.charAt(i);
            char next = i + 1 < current.length() ? current.charAt(i + 1) : '\0';
            if (blockComment) {
                if (value == '*' && next == '/') { blockComment = false; i++; }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && value == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && value == '-' && next == '-') {
                int newline = current.indexOf("\n", i + 2);
                if (newline < 0) return;
                i = newline;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && (value == 'q' || value == 'Q') && next == '\''
                    && i + 2 < current.length()) {
                char opening = current.charAt(i + 2);
                char closing = switch (opening) {
                    case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>';
                    default -> opening;
                };
                int end = current.indexOf(String.valueOf(closing) + '\'', i + 3);
                if (end < 0) return;
                i = end + 1;
                continue;
            }
            if (!doubleQuoted && value == '\'') {
                if (singleQuoted && next == '\'') { i++; continue; }
                singleQuoted = !singleQuoted;
            } else if (!singleQuoted && value == '"') {
                if (doubleQuoted && next == '"') { i++; continue; }
                doubleQuoted = !doubleQuoted;
            } else if (value == ';' && !singleQuoted && !doubleQuoted) {
                String statement = current.substring(0, i).trim();
                if (!statement.isEmpty()) result.add(statement);
                current.delete(0, i + 1);
                i = -1;
            }
        }
    }

    private static void add(List<String> result, StringBuilder value, boolean preserveFinalSemicolon) {
        String statement = value.toString().trim();
        if (!preserveFinalSemicolon) {
            while (statement.endsWith(";")) statement = statement.substring(0, statement.length() - 1).trim();
        }
        if (!statement.isEmpty()) result.add(statement);
        value.setLength(0);
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM user_tables WHERE table_name=?")) {
            statement.setString(1, table);
            try (ResultSet row = statement.executeQuery()) { row.next(); return row.getInt(1) > 0; }
        }
    }

    private static void resetRepository(Connection connection, String configuredUser)
            throws SQLException {
        String actualUser;
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual")) {
            row.next();
            actualUser = row.getString(1);
        }
        if (!actualUser.equalsIgnoreCase(configuredUser)) {
            throw new SQLException("Repository reset refused: connected schema " + actualUser
                    + " differs from configured repository user " + configuredUser);
        }
        int tableCount;
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM user_tables")) {
            row.next();
            tableCount = row.getInt(1);
        }
        boolean privonymRepository = tableExists(connection, "ALGORITHM")
                || tableExists(connection, "ANONYMIZATION_JOB")
                || tableExists(connection, "PRIVONYM_SCHEMA_MIGRATION");
        if (tableCount > 0 && !privonymRepository) {
            throw new SQLException("Repository reset refused: schema " + actualUser
                    + " contains tables but was not recognized as a Privonym repository");
        }

        List<SchemaObject> objects = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("""
                SELECT object_name,object_type FROM user_objects
                 WHERE object_type IN ('MATERIALIZED VIEW','VIEW','TRIGGER','TABLE','SEQUENCE',
                                       'PACKAGE','PROCEDURE','FUNCTION','TYPE','SYNONYM')
                   AND object_name NOT LIKE 'BIN$%'
                 ORDER BY CASE object_type
                    WHEN 'MATERIALIZED VIEW' THEN 1 WHEN 'VIEW' THEN 2 WHEN 'TRIGGER' THEN 3
                    WHEN 'TABLE' THEN 4 WHEN 'SEQUENCE' THEN 5 WHEN 'PACKAGE' THEN 6
                    WHEN 'PROCEDURE' THEN 7 WHEN 'FUNCTION' THEN 8 WHEN 'TYPE' THEN 9 ELSE 10 END
                """)) {
            while (row.next()) objects.add(new SchemaObject(row.getString(1), row.getString(2)));
        }
        System.out.printf(Locale.ROOT,
                "RESET REQUESTED: removing %d object(s) from Oracle repository schema %s.%n",
                objects.size(), actualUser);
        for (SchemaObject object : objects) {
            String suffix = switch (object.type()) {
                case "TABLE" -> " CASCADE CONSTRAINTS PURGE";
                case "TYPE" -> " FORCE";
                default -> "";
            };
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP " + object.type() + " "
                        + quotedIdentifier(object.name()) + suffix);
            } catch (SQLException error) {
                if (isMissingObject(error)) {
                    System.out.printf(Locale.ROOT,
                            "Reset notice: %s %s was already absent; continuing.%n",
                            object.type(), object.name());
                    continue;
                }
                throw new SQLException("Unable to reset repository schema " + actualUser
                        + ": failed to drop " + object.type() + " " + object.name()
                        + ": " + error.getMessage(), error);
            }
        }
        connection.commit();
        System.out.println("Oracle repository schema cleared; starting clean Privonym installation.");
    }

    private static boolean isMissingObject(SQLException error) {
        // ORA-04043 object does not exist, ORA-00942 table or view does not exist,
        // ORA-02289 sequence does not exist. A dependency may have removed an
        // object after USER_OBJECTS was read, so reset remains idempotent.
        return error.getErrorCode() == 4043 || error.getErrorCode() == 942
                || error.getErrorCode() == 2289;
    }

    private static String quotedIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record SchemaObject(String name, String type) { }

    private static void verifyModel(Connection connection) throws SQLException {
        String sql = "SELECT (SELECT COUNT(*) FROM user_tables), (SELECT COUNT(*) FROM algorithm), "
                + "(SELECT COUNT(*) FROM database_technology), (SELECT COUNT(*) FROM sensitive_data_type) FROM dual";
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            if (row.getInt(1) < 30 || row.getInt(2) < 1 || row.getInt(3) < 3 || row.getInt(4) < 1) {
                throw new SQLException("Post-install verification failed: incomplete model or system metadata");
            }
        }
    }

    private static void verifySystemMetadata(Connection connection) throws SQLException {
        String sql = """
                SELECT
                  (SELECT COUNT(*) FROM algorithm WHERE status='ACTIVE'),
                  (SELECT COUNT(*) FROM algorithm WHERE code='LOOKUP_COMPANY'),
                  (SELECT COUNT(*) FROM lookup_dataset_value v JOIN lookup_dataset d
                    ON d.lookup_dataset_id=v.lookup_dataset_id
                   WHERE d.code='LOOKUP_LAST_NAME_VALUES' AND v.enabled='Y'),
                  (SELECT COUNT(*) FROM lookup_dataset_value v JOIN lookup_dataset d
                    ON d.lookup_dataset_id=v.lookup_dataset_id
                   WHERE d.code='LOOKUP_FIRST_NAME_VALUES' AND v.enabled='Y'),
                  (SELECT COUNT(*) FROM lookup_dataset_value v JOIN lookup_dataset d
                    ON d.lookup_dataset_id=v.lookup_dataset_id
                   WHERE d.code='LOOKUP_CITY_VALUES' AND v.enabled='Y'),
                  (SELECT COUNT(*) FROM lookup_dataset_value v JOIN lookup_dataset d
                    ON d.lookup_dataset_id=v.lookup_dataset_id
                   WHERE d.code='LOOKUP_ADDRESS_VALUES' AND v.enabled='Y'),
                  (SELECT COUNT(*) FROM lookup_dataset_value v JOIN lookup_dataset d
                    ON d.lookup_dataset_id=v.lookup_dataset_id
                   WHERE d.code='LOOKUP_COMPANY_VALUES' AND v.enabled='Y'),
                  (SELECT COUNT(*) FROM privonym_privilege)
                FROM dual
                """;
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            if (row.getInt(1) < 20 || row.getInt(2) != 1 || row.getInt(3) < 200
                    || row.getInt(4) < 200 || row.getInt(5) < 150
                    || row.getInt(6) < 1000 || row.getInt(7) < 80
                    || row.getInt(8) < 10) {
                throw new SQLException("Post-install verification failed: incomplete algorithm, lookup or privilege metadata");
            }
        }
    }

    private static void verifyPristineUserMetadata(Connection connection) throws SQLException {
        List<String> userTables = List.of(
                "ENVIRONMENT", "CONNECTOR", "DATA_SOURCE", "SECRET_REFERENCE",
                "CATALOG_SCHEMA", "CATALOG_TABLE", "CATALOG_COLUMN",
                "CATALOG_RELATIONSHIP", "CATALOG_RELATIONSHIP_COLUMN",
                "COLUMN_CLASSIFICATION", "CONSISTENCY_DOMAIN", "RULESET",
                "RULESET_VERSION", "MASKING_RULE", "TABLE_MASKING_RULE",
                "RULE_PARAMETER", "ANONYMIZATION_JOB", "JOB_STEP",
                "JOB_STEP_DEPENDENCY", "JOB_STEP_ATTEMPT", "JOB_CHUNK",
                "JOB_EVENT", "JOB_METRIC", "VALIDATION_RESULT", "PRIVONYM_USER",
                "PRIVONYM_USER_PRIVILEGE", "PRIVONYM_SECURITY_EVENT");
        for (String table : userTables) {
            try (Statement statement = connection.createStatement();
                    ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                row.next();
                if (row.getLong(1) != 0) {
                    throw new SQLException("Fresh repository contains user metadata in " + table);
                }
            }
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String abbreviated(String sql) {
        String value = sql.replaceAll("\\s+", " ").trim();
        return value.length() <= 160 ? value : value.substring(0, 157) + "...";
    }
}
