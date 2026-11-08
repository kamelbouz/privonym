package com.privonym.install;

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

/** Applies the bundled Oracle repository migrations exactly once. */
public final class OracleRepositoryUpgrader {
    private static final String HISTORY_TABLE = "PRIVONYM_SCHEMA_MIGRATION";

    private OracleRepositoryUpgrader() { }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: OracleRepositoryUpgrader <properties> <migration-directory>");
            System.exit(2);
        }
        char[] password = new char[0];
        try {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(Path.of(args[0]))) {
                properties.load(input);
            }
            String url = required(properties, "repository.url");
            String user = required(properties, "repository.user");
            Console console = System.console();
            if (console == null) {
                throw new IllegalStateException("An interactive console is required to enter the repository password");
            }
            password = console.readPassword("Repository password for %s: ", user);
            if (password == null || password.length == 0) {
                throw new IllegalArgumentException("Repository password is required");
            }
            Path migrationDirectory = Path.of(args[1]);
            List<Path> migrations = migrations(migrationDirectory);
            if (migrations.isEmpty()) {
                throw new IOException("No Oracle migration was found in " + migrationDirectory);
            }

            try (Connection connection = DriverManager.getConnection(url, user, new String(password))) {
                connection.setAutoCommit(false);
                requireExistingRepository(connection);
                int[] result = applyAll(connection, migrations, user);
                verify(connection);
                System.out.printf(Locale.ROOT,
                        "Privonym Oracle repository upgrade completed: %d applied, %d already present.%n",
                        result[0], result[1]);
            }
        } catch (Exception error) {
            System.err.println("ERROR: Unable to upgrade Privonym repository: " + error.getMessage());
            System.exit(1);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    static int[] applyAll(Connection connection, Path migrationDirectory, String user) throws Exception {
        List<Path> found = migrations(migrationDirectory);
        if (found.isEmpty()) throw new IOException("No Oracle migration was found in " + migrationDirectory);
        return applyAll(connection, found, user);
    }

    private static int[] applyAll(Connection connection, List<Path> migrations, String user) throws Exception {
        ensureHistoryTable(connection);
        // Refuse to replay earlier scripts against a repository with a partial baseline.
        String firstMissing = null;
        for (Path migration : migrations) {
            String name = migration.getFileName().toString();
            String recorded = recordedChecksum(connection, name);
            if (recorded == null && firstMissing == null) firstMissing = name;
            if (recorded != null) {
                if (firstMissing != null) throw new IllegalStateException(
                        "Incomplete migration history: " + firstMissing + " is missing before " + name
                        + ". Reconcile the installed schema with a verified baseline before upgrading.");
                if (!matchesChecksum(migration,recorded)) throw new IllegalStateException(
                        "Migration checksum mismatch: " + name);
            }
        }
        int applied = 0;
        int skipped = 0;
        for (Path migration : migrations) {
            String name = migration.getFileName().toString();
            String checksum = sha256(migration);
            String recordedChecksum = recordedChecksum(connection, name);
            if (recordedChecksum != null) {
                if (!matchesChecksum(migration,recordedChecksum)) {
                    throw new IllegalStateException("Migration " + name
                            + " was already recorded with a different SHA-256 checksum");
                }
                System.out.println(OracleRepositoryReconciler.adoptedChecksum(connection,name)!=null?"[BASELINE] "+name+" observed runtime state adopted; historical script not replayed":"[SKIP] "+name+" already applied");
                skipped++;
                continue;
            }
            System.out.println("[APPLY] " + name);
            apply(connection, migration);
            record(connection, name, checksum, user);
            connection.commit();
            applied++;
        }
        return new int[] {applied, skipped};
    }

    private static List<Path> migrations(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Migration directory not found: " + directory);
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("V[0-9_]+.*_oracle\\.sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    private static void requireExistingRepository(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM user_tables WHERE table_name='ALGORITHM'");
             ResultSet row = statement.executeQuery()) {
            row.next();
            if (row.getInt(1) == 0) {
                throw new SQLException("ALGORITHM table not found; install the baseline before running an upgrade");
            }
        }
    }

    private static void ensureHistoryTable(Connection connection) throws SQLException {
        String block = "BEGIN "
                + "EXECUTE IMMEDIATE 'CREATE TABLE " + HISTORY_TABLE + " ("
                + "migration_name VARCHAR2(200 CHAR) NOT NULL, "
                + "checksum_sha256 VARCHAR2(64 CHAR) NOT NULL, "
                + "applied_at TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL, "
                + "applied_by VARCHAR2(128 CHAR) NOT NULL, "
                + "CONSTRAINT privonym_schema_migration_pk PRIMARY KEY (migration_name))'; "
                + "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;";
        try (Statement statement = connection.createStatement()) {
            statement.execute(block);
        }
    }

    static String recordedChecksum(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT checksum_sha256 FROM " + HISTORY_TABLE + " WHERE migration_name=?")) {
            statement.setString(1, name);
            try (ResultSet row = statement.executeQuery()) {
                if(row.next())return row.getString(1);
            }
        }
        return OracleRepositoryReconciler.adoptedChecksum(connection,name);
    }

    static void apply(Connection connection, Path migration) throws IOException, SQLException {
        String script = Files.readString(migration, StandardCharsets.UTF_8);
        for (String sql : OracleRepositoryInstaller.statements(script)) {
            if (sql.equalsIgnoreCase("COMMIT")) {
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw new SQLException(migration.getFileName() + ": " + abbreviated(sql)
                        + ": " + error.getMessage(), error);
            }
        }
    }

    private static void record(Connection connection, String name, String checksum, String user) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + HISTORY_TABLE
                        + " (migration_name, checksum_sha256, applied_by) VALUES (?, ?, ?)")) {
            statement.setString(1, name);
            statement.setString(2, checksum);
            statement.setString(3, user);
            statement.executeUpdate();
        }
    }

    private static void verify(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + HISTORY_TABLE);
             ResultSet row = statement.executeQuery()) {
            row.next();
            if (row.getInt(1) == 0) {
                throw new SQLException("Post-upgrade verification failed: no migration was recorded");
            }
        }
    }

    static boolean matchesChecksum(Path path,String recorded)throws IOException,NoSuchAlgorithmException {
        if(recorded.equalsIgnoreCase(sha256(path)))return true;
        String lf=Files.readString(path,StandardCharsets.UTF_8).replace("\r\n","\n");
        for(String text:List.of(lf,lf.replace("\n","\r\n"))) {
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            if(recorded.equalsIgnoreCase(digest))return true;
        }
        return false;
    }

    static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String abbreviated(String sql) {
        String value = sql.replaceAll("\\s+", " ").trim();
        return value.length() <= 160 ? value : value.substring(0, 157) + "...";
    }
}
