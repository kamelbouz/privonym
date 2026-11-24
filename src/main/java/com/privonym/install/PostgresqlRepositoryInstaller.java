package com.privonym.install;

import com.privonym.http.ApplicationSecurity;
import com.privonym.repository.RepositoryConnections;
import com.privonym.repository.oracle.OracleDiscoveryAdapter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** Transactional installation in an empty PostgreSQL database. Never overwrites an existing repository. */
public final class PostgresqlRepositoryInstaller {
    private PostgresqlRepositoryInstaller() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: PostgresqlRepositoryInstaller CONFIG PROJECT_ROOT");
        Properties properties = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) { properties.load(input); }
        String url = properties.getProperty("repository.url", "");
        if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("A PostgreSQL repository URL is required");
        String password = System.getenv("PRIVONYM_REPOSITORY_PASSWORD");
        if (password == null) {
            if (System.console() == null) throw new IllegalStateException("An interactive console or PRIVONYM_REPOSITORY_PASSWORD is required");
            char[] entered = System.console().readPassword("PostgreSQL repository password: ");
            if (entered == null) throw new IllegalStateException("No repository password supplied");
            password = new String(entered); java.util.Arrays.fill(entered, '\0');
        }
        try (Connection connection = RepositoryConnections.open(url, properties.getProperty("repository.user"), password)) {
            install(connection, Path.of(args[1]));
        }
        System.out.println("PostgreSQL repository initialized. No Oracle data was changed or transferred.");
    }

    public static void install(Connection connection, Path root) throws Exception {
        if (!connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            throw new IllegalArgumentException("PostgreSQL is required");
        }
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(1782469013)");
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema()")) {
                    rows.next();
                    if (rows.getLong(1) != 0) throw new SQLException("Installation requires an empty schema; no existing tables will be replaced");
                }
            }
            executeNative(connection, root.resolve("database/postgresql/V001__repository.sql"));
            executeNative(connection, root.resolve("database/postgresql/V002__repository_compatibility.sql"));
            String seed = Files.readString(root.resolve("database/generated/V2_001_1_privonym_system_metadata_oracle.sql"));
            for (String sql : OracleRepositoryInstaller.statements(seed)) {
                if (sql.strip().equalsIgnoreCase("COMMIT")) continue;
                if (!sql.stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("INSERT INTO ")) {
                    throw new SQLException("Unexpected command in the product-owned initial catalog");
                }
                try (Statement statement = connection.createStatement()) { statement.executeUpdate(sql); }
            }
            resetSequences(connection);
            OracleDiscoveryAdapter.provisionPortableSystemCatalog(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO privonym_privilege(privilege_code,description) VALUES(?,?)")) {
                for (String code : ApplicationSecurity.AVAILABLE_PRIVILEGES.stream().sorted().toList()) {
                    statement.setString(1, code); statement.setString(2, code.replace('_', ' ')); statement.addBatch();
                }
                statement.executeBatch();
            }
            installExactExpressions(connection);
            executeNative(connection, root.resolve("database/postgresql/V003__repository_invariants.sql"));
            executeNative(connection, root.resolve("database/postgresql/V004__repository_indexes.sql"));
            recordNativeMigrations(connection, root);
            connection.commit();
        } catch (Exception error) {
            connection.rollback(); throw error;
        }
    }

    static void recordNativeMigrations(Connection connection, Path root) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO privonym_schema_migration(migration_name,checksum_sha256,applied_by)
                VALUES(?,?,current_user)
                """)) {
            for (String file : List.of("V001__repository.sql", "V002__repository_compatibility.sql",
                    "V003__repository_invariants.sql", "V004__repository_indexes.sql")) {
                byte[] bytes = Files.readAllBytes(root.resolve("database/postgresql").resolve(file));
                insert.setString(1,"POSTGRESQL_" + file);
                insert.setString(2,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    static void executeNative(Connection connection, Path script) throws Exception {
        // Native function bodies must bypass the legacy repository statement renderer.
        try (Connection raw = new NonClosingConnection(connection).nativeConnection()) {
            for (String sql : PostgresqlScripts.statements(Files.readString(script))) {
                try (Statement statement = raw.createStatement()) { statement.execute(sql); }
            }
        }
    }

    public static void resetSequences(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT table_name,column_name FROM information_schema.columns
                WHERE table_schema=current_schema() AND is_identity='YES'
                """)) {
            while (rows.next()) {
                String table = rows.getString(1), column = rows.getString(2);
                if (!table.matches("[a-z_]+") || !column.matches("[a-z_]+")) throw new SQLException("Unexpected repository identifier");
                try (Statement reset = connection.createStatement()) {
                    reset.execute("SELECT setval(pg_get_serial_sequence('" + table + "','" + column
                            + "'),GREATEST(COALESCE(MAX(" + column + "),0),1),MAX(" + column + ") IS NOT NULL) FROM " + table);
                }
            }
        }
        try (Statement reset = connection.createStatement()) {
            reset.execute("SELECT setval('job_script_seq',GREATEST(COALESCE(MAX(job_script_id),0),1),MAX(job_script_id) IS NOT NULL) FROM job_script");
        }
    }

    private static void installExactExpressions(Connection connection) throws Exception {
        for (String engine : List.of("ORACLE", "POSTGRESQL", "SQLSERVER")) {
            String code = engine + "_EXPRESSION_EXACT";
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO algorithm(code,name,category,deterministic,preserve_null)
                    VALUES(?,?,'MASKING','N','N') RETURNING algorithm_id
                    """)) {
                insert.setString(1, code); insert.setString(2, "Imported " + engine + " expression");
                long algorithm;
                try (ResultSet row = insert.executeQuery()) { row.next(); algorithm = row.getLong(1); }
                long version;
                try (PreparedStatement v = connection.prepareStatement("""
                        INSERT INTO algorithm_version(algorithm_id,version_number,status,created_by,published_at)
                        VALUES(?,1,'PUBLISHED','POSTGRESQL_INITIAL_SEED',statement_timestamp()) RETURNING algorithm_version_id
                        """)) {
                    v.setLong(1, algorithm); try (ResultSet row = v.executeQuery()) { row.next(); version = row.getLong(1); }
                }
                try (PreparedStatement p = connection.prepareStatement("""
                        INSERT INTO algorithm_parameter_def(algorithm_version_id,parameter_name,data_type,required)
                        VALUES(?,'expression','STRING','Y')
                        """)) { p.setLong(1, version); p.executeUpdate(); }
                String template = "PRIVONYM_IMPORT_RUNTIME_V" + (engine.equals("ORACLE") ? "26" : "27") + "_REQUIRED(${column})";
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(template.getBytes(StandardCharsets.UTF_8)));
                try (PreparedStatement p = connection.prepareStatement("""
                        INSERT INTO algorithm_implementation(algorithm_version_id,technology_code,implementation_type,
                        implementation_code,code_checksum,status,published_at)
                        VALUES(?,?,'SQL_EXPRESSION',?,?,'PUBLISHED',statement_timestamp())
                        """)) {
                    p.setLong(1, version); p.setString(2, engine); p.setString(3, template); p.setString(4, hash); p.executeUpdate();
                }
            }
        }
    }

    /** Unwrap a driver connection without changing its ownership or closing it. */
    private record NonClosingConnection(Connection connection) {
        Connection nativeConnection() throws SQLException {
            Connection raw = connection.unwrap(Connection.class);
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) return null;
                        try { return method.invoke(raw, args); }
                        catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                    });
        }
    }
}
