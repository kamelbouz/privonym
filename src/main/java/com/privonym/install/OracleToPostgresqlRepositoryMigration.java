package com.privonym.install;

import com.privonym.repository.RepositoryConnections;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/** Consistent, verified copy. Oracle is read-only; destination creation and rows commit atomically. */
public final class OracleToPostgresqlRepositoryMigration {
    private OracleToPostgresqlRepositoryMigration() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !List.of("snapshot", "migrate-stopped").contains(args[0])) {
            throw new IllegalArgumentException("Usage: OracleToPostgresqlRepositoryMigration snapshot|migrate-stopped SOURCE_CONFIG TARGET_CONFIG PROJECT_ROOT. Stop all source runners before migrate-stopped.");
        }
        Properties source = load(Path.of(args[1])), target = load(Path.of(args[2]));
        String sourceUrl = source.getProperty("repository.url");
        if (sourceUrl == null) sourceUrl = "jdbc:oracle:thin:@//" + required(source,"repository.host") + ":"
                + source.getProperty("repository.port","1521") + "/" + required(source,"repository.service");
        String targetUrl = required(target,"repository.url");
        if (!sourceUrl.startsWith("jdbc:oracle:") || !targetUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Expected Oracle source and PostgreSQL destination");
        }
        if (args[0].equals("snapshot") && !targetUrl.matches("jdbc:postgresql://[^/]+/privonym_migration_test_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Snapshots require a disposable privonym_migration_test_* database");
        }
        String sourcePassword = source.getProperty("repository.password", System.getenv("PRIVONYM_SOURCE_PASSWORD"));
        String targetPassword = System.getenv("PRIVONYM_REPOSITORY_PASSWORD");
        if (sourcePassword == null || targetPassword == null) throw new IllegalArgumentException("Repository credentials are required; passwords must not be command arguments");
        try (Connection from = DriverManager.getConnection(sourceUrl, required(source,"repository.user"),sourcePassword);
                Connection to = RepositoryConnections.open(targetUrl,required(target,"repository.user"),targetPassword)) {
            copy(from,to,Path.of(args[3]));
        }
    }

    public static void copy(Connection source, Connection target, Path root) throws Exception {
        if (!source.getMetaData().getDatabaseProductName().equals("Oracle")
                || !target.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            throw new IllegalArgumentException("Expected Oracle and PostgreSQL connections");
        }
        source.setAutoCommit(false);
        target.setAutoCommit(false);
        try {
            try (Statement s = source.createStatement()) { s.execute("SET TRANSACTION READ ONLY"); }
            try (Statement s = target.createStatement()) {
                s.execute("SELECT pg_advisory_xact_lock(1782469013)");
                try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema()")) {
                    r.next(); if (r.getLong(1) != 0) throw new SQLException("Destination schema must be empty; no existing repository will be overwritten");
                }
            }
            try (Statement s = source.createStatement(); ResultSet r = s.executeQuery("""
                    SELECT COUNT(*) FROM anonymization_job
                    WHERE status IN('RUNNING','RETRYING','CANCELLING','PAUSING','PLANNING','PREFLIGHTING','VALIDATING')
                    """)) {
                r.next(); if (r.getLong(1) != 0) throw new SQLException("Active or interrupted source executions require resolution before migration; no states were changed");
            }
            PostgresqlRepositoryInstaller.executeNative(target,root.resolve("database/postgresql/V001__repository.sql"));
            PostgresqlRepositoryInstaller.executeNative(target,root.resolve("database/postgresql/V002__repository_compatibility.sql"));
            List<String> tables = new ArrayList<>();
            try (Statement s = target.createStatement(); ResultSet r = s.executeQuery("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema=current_schema() AND table_type='BASE TABLE' ORDER BY table_name
                    """)) { while(r.next()) tables.add(identifier(r.getString(1))); }
            List<String> sourceTables = new ArrayList<>();
            try (Statement s = source.createStatement(); ResultSet r = s.executeQuery("SELECT LOWER(table_name) FROM user_tables WHERE table_name NOT LIKE 'BIN$%' ORDER BY table_name")) {
                while(r.next()) sourceTables.add(identifier(r.getString(1)));
            }
            // Oracle and PostgreSQL may use different database collations.
            tables.sort(String::compareTo);
            sourceTables.sort(String::compareTo);
            if (!tables.equals(sourceTables)) throw new SQLException("Source repository structure differs from the supported baseline; migration refused");
            try (Statement s = target.createStatement()) { s.execute("SET CONSTRAINTS ALL DEFERRED"); }
            long total = 0;
            for (String table : tables) {
                List<Column> columns = columns(target,table);
                List<String> sourceColumns = new ArrayList<>();
                try (PreparedStatement q = source.prepareStatement("SELECT LOWER(column_name) FROM user_tab_columns WHERE table_name=? ORDER BY column_id")) {
                    q.setString(1,table.toUpperCase(Locale.ROOT));
                    try (ResultSet r = q.executeQuery()) { while(r.next()) sourceColumns.add(r.getString(1)); }
                }
                if (!columns.stream().map(Column::name).toList().equals(sourceColumns)) {
                    throw new SQLException("Column layout differs for " + table + "; migration refused");
                }
                Fingerprint expected = copyTable(source,target,table,columns);
                Fingerprint actual = fingerprint(target,table,columns);
                if (!expected.equals(actual)) throw new SQLException("Verification failed for " + table + "; destination rolled back");
                total += actual.rows();
                System.out.println("Verified " + table + ": " + actual.rows() + " rows");
            }
            try (Statement s = target.createStatement()) { s.execute("SET CONSTRAINTS ALL IMMEDIATE"); }
            PostgresqlRepositoryInstaller.resetSequences(target);
            PostgresqlRepositoryInstaller.executeNative(target,root.resolve("database/postgresql/V003__repository_invariants.sql"));
            PostgresqlRepositoryInstaller.executeNative(target,root.resolve("database/postgresql/V004__repository_indexes.sql"));
            PostgresqlRepositoryInstaller.recordNativeMigrations(target,root);
            target.commit();
            System.out.println("Repository copy verified: " + tables.size() + " tables, " + total + " rows. Preserve the original credential encryption key. Oracle was not modified.");
        } catch (Exception error) {
            target.rollback(); throw error;
        } finally {
            source.rollback();
        }
    }

    private static Fingerprint copyTable(Connection from, Connection to, String table, List<Column> columns) throws Exception {
        String select = "SELECT " + String.join(",",columns.stream().map(c -> "\"" + c.name().toUpperCase(Locale.ROOT) + "\"").toList())
                + " FROM \"" + table.toUpperCase(Locale.ROOT) + "\"";
        String insert = "INSERT INTO " + table + " (" + String.join(",",columns.stream().map(Column::name).toList())
                + ") VALUES (" + String.join(",",java.util.Collections.nCopies(columns.size(),"?")) + ")";
        Accumulator accumulator = new Accumulator();
        try (Statement read = from.createStatement(); PreparedStatement write = to.prepareStatement(insert)) {
            read.setFetchSize(200);
            try (ResultSet rows = read.executeQuery(select)) {
                int pending = 0;
                while(rows.next()) {
                    Object[] values = values(rows,columns);
                    accumulator.add(values);
                    for (int i = 0; i < values.length; i++) write.setObject(i+1,values[i]);
                    write.addBatch();
                    if (++pending == 200) { write.executeBatch(); write.clearBatch(); pending = 0; }
                }
                if (pending != 0) write.executeBatch();
            }
        }
        return accumulator.finish();
    }

    private static Fingerprint fingerprint(Connection c, String table, List<Column> columns) throws Exception {
        Accumulator accumulator = new Accumulator();
        try (Statement query = c.createStatement(); ResultSet rows = query.executeQuery("SELECT "
                + String.join(",",columns.stream().map(Column::name).toList()) + " FROM " + table)) {
            while(rows.next()) accumulator.add(values(rows,columns));
        }
        return accumulator.finish();
    }

    private static Object[] values(ResultSet rows, List<Column> columns) throws SQLException {
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < values.length; i++) {
            int index = i+1;
            values[i] = switch(columns.get(i).type()) {
                case "bigint", "numeric" -> rows.getBigDecimal(index);
                case "timestamp with time zone" -> rows.getObject(index,OffsetDateTime.class);
                case "timestamp without time zone" -> rows.getTimestamp(index);
                case "bytea" -> rows.getBytes(index);
                default -> rows.getString(index);
            };
            if (values[i] instanceof OffsetDateTime date && date.getNano()%1000 != 0) {
                throw new SQLException("Timestamp precision would be lost in column " + columns.get(i).name());
            }
        }
        return values;
    }

    private static List<Column> columns(Connection c,String table) throws SQLException {
        List<Column> columns = new ArrayList<>();
        try (PreparedStatement q = c.prepareStatement("SELECT column_name,data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=? ORDER BY ordinal_position")) {
            q.setString(1,table); try(ResultSet r = q.executeQuery()) {
                while(r.next()) columns.add(new Column(identifier(r.getString(1)),r.getString(2)));
            }
        }
        return List.copyOf(columns);
    }
    private static String identifier(String value) throws SQLException {
        if (!value.matches("[a-z][a-z0-9_]*")) throw new SQLException("Unexpected repository identifier");
        return value;
    }
    private static Properties load(Path path) throws Exception {
        Properties p = new Properties(); try (var input = Files.newInputStream(path)) { p.load(input); } return p;
    }
    private static String required(Properties p,String key) {
        return Objects.requireNonNull(p.getProperty(key),"Missing " + key);
    }
    private record Column(String name,String type) { }
    private record Fingerprint(long rows,BigInteger digest) { }
    private static final class Accumulator {
        private static final BigInteger MASK = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
        private final MessageDigest sha;
        private BigInteger sum = BigInteger.ZERO;
        private long rows;
        Accumulator() throws Exception { sha = MessageDigest.getInstance("SHA-256"); }
        void add(Object[] values) {
            sha.reset();
            for (Object value : values) {
                if (value == null) { sha.update((byte)0); continue; }
                sha.update((byte)1);
                byte[] bytes;
                if (value instanceof byte[] binary) bytes = binary;
                else {
                    String normalized = value instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString()
                            : value instanceof OffsetDateTime date ? date.toInstant().toString()
                            : value instanceof java.sql.Timestamp date ? date.toLocalDateTime().toString() : value.toString();
                    bytes = normalized.getBytes(StandardCharsets.UTF_8);
                }
                sha.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); sha.update(bytes);
            }
            sum = sum.add(new BigInteger(1,sha.digest())).and(MASK); rows++;
        }
        Fingerprint finish() { return new Fingerprint(rows,sum); }
    }
}
