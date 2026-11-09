package com.privonym.repository.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Optional read-only driver qualification: no table reads, job changes or business DML. */
public final class SqlServerCountCancellationIntegrationCheck {
    private SqlServerCountCancellationIntegrationCheck() { }

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) { properties.load(input); }
        String url = properties.getProperty("repository.url");
        if (url == null || url.isBlank()) url = "jdbc:oracle:thin:@//"
                + properties.getProperty("repository.host") + ":"
                + properties.getProperty("repository.port", "1521") + "/"
                + properties.getProperty("repository.service");
        try (var repository = DriverManager.getConnection(url,
                properties.getProperty("repository.user"), properties.getProperty("repository.password"))) {
            repository.setAutoCommit(false);
            repository.setReadOnly(true);
            long sourceId;
            String encrypted;
            try (var query = repository.prepareStatement("SELECT j.data_source_id,c.encrypted_password FROM anonymization_job j JOIN data_source d ON d.data_source_id=j.data_source_id JOIN connector c ON c.connector_id=d.connector_id WHERE j.job_id=?")) {
                query.setLong(1, Long.parseLong(args[1]));
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Job not found");
                    sourceId = rows.getLong(1); encrypted = rows.getString(2);
                }
            }
            var details = new OraclePlanningAdapter(repository).loadConnectionDetails(
                    new com.privonym.domain.catalog.DataSourceId(sourceId));
            if (!details.technologyCode().equals("SQLSERVER")) throw new IllegalArgumentException("SQL Server required");
            char[] password = LocalCredentialStore.decrypt(encrypted);
            try (var target = DriverManager.getConnection(details.jdbcUrl(), details.username(), new String(password));
                    var statement = target.prepareStatement("WAITFOR DELAY '00:00:20'; SELECT 1")) {
                target.setAutoCommit(false);
                target.setReadOnly(true);
                Set<Statement> tracked = ConcurrentHashMap.newKeySet();
                CountDownLatch entered = new CountDownLatch(1);
                AtomicReference<String> phase = new AtomicReference<>("executeQuery");
                var worker = Executors.newSingleThreadExecutor();
                try {
                    var future = worker.submit(() -> {
                        try (var rows = CancellableQueries.execute(statement,
                                active -> { tracked.add(active); entered.countDown(); }, tracked::remove, () -> { })) {
                            phase.set("ResultSet.next");
                            rows.next();
                            throw new AssertionError("Delayed query completed without cancellation");
                        } catch (SQLException expected) {
                            return expected.getSQLState();
                        }
                    });
                    if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Query did not start");
                    Thread.sleep(500);
                    String waitingPhase = phase.get();
                    long start = System.nanoTime();
                    for (Statement active : tracked) active.cancel();
                    String state = future.get(5, TimeUnit.SECONDS);
                    if (!"HY008".equals(state)) throw new AssertionError("Unexpected cancellation SQL state: " + state);
                    if (!tracked.isEmpty()) throw new AssertionError("Tracking leaked after cancellation");
                    System.out.println("Real SQL Server cancellation passed at " + waitingPhase
                            + ": SQLState=" + state + ", elapsedMs="
                            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                            + "; no business tables or existing jobs changed.");
                } finally {
                    statement.cancel();
                    worker.shutdownNow();
                    target.rollback();
                }
            } finally { Arrays.fill(password, '\0'); }
            repository.rollback();
        }
    }
}
