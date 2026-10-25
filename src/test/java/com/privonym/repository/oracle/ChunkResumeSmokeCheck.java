package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.SqlChecksum;
import com.privonym.application.service.ExecutableStep;
import com.privonym.domain.job.PlanChecksum;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Executes real resume control flow with synthetic JDBC state and no business database. */
final class ChunkResumeSmokeCheck implements Driver {
    private record Chunk(String status, String end, long rows, String error) { }
    private final Map<Integer,Chunk> chunks = new ConcurrentHashMap<>();
    private final Map<Integer,AtomicInteger> applications = new ConcurrentHashMap<>();
    private final String targetUrl;

    private ChunkResumeSmokeCheck(String technology, boolean lastAlreadySucceeded) {
        targetUrl = switch (technology) {
            case "ORACLE" -> "jdbc:oracle:thin:audit-resume-target";
            case "SQLSERVER" -> "jdbc:sqlserver://audit-resume-target";
            default -> "jdbc:postgresql:audit-resume-target";
        };
        for (int number = 1; number <= 8; number++) {
            boolean done = number <= 6 || number == 8 && lastAlreadySucceeded;
            chunks.put(number, new Chunk(done ? "SUCCEEDED" : "FAILED",
                    Integer.toString(number * 1000), done ? 1000 : 0, null));
            applications.put(number, new AtomicInteger(done ? 1 : 0));
        }
    }

    static void verify() throws Exception {
        List<Driver> original = Collections.list(DriverManager.getDrivers());
        for (Driver driver : original) DriverManager.deregisterDriver(driver);
        try {
            for (String technology : List.of("ORACLE", "POSTGRESQL", "SQLSERVER")) {
                for (boolean lastDone : new boolean[] {false, true}) {
                    ChunkResumeSmokeCheck driver = new ChunkResumeSmokeCheck(technology, lastDone);
                    DriverManager.registerDriver(driver);
                    try { driver.resume(technology); }
                    finally { DriverManager.deregisterDriver(driver); }
                }
            }
        } finally {
            for (Driver driver : original) DriverManager.registerDriver(driver);
        }
        System.out.println("Chunk resume passed: successful chunks preserved; only failed ranges retried on all three dialects.");
    }

    private void resume(String technology) throws Exception {
        try (OracleParallelPlanExecutor executor = new OracleParallelPlanExecutor(
                "jdbc:audit:resume-repository", "test", new char[0], targetUrl,
                "test", new char[0], 2, "CHUNKED", 1000, 1000);
                Connection repository = connect("jdbc:audit:resume-repository", new Properties())) {
            Class<?> outer = OracleParallelPlanExecutor.class;
            // Exercise the production guard and load the six confirmed checkpoints.
            var checkpoints = outer.getDeclaredMethod("successfulChunks", Connection.class, long.class);
            checkpoints.setAccessible(true);
            Object completed = checkpoints.invoke(null, repository, 90L);
            String sql = "UPDATE \"DEMO\".\"CUSTOMER\" SET \"VALUE\"=1";
            var step = new ExecutableStep(90, 20, sql, new PlanChecksum(SqlChecksum.sha256(sql)));
            var parse = outer.getDeclaredMethod("operation", String.class);
            parse.setAccessible(true);
            Object operation = parse.invoke(null, sql);
            Class<?> keyClass = Class.forName(outer.getName() + "$PrimaryKey");
            var keyConstructor = keyClass.getDeclaredConstructors()[0];
            keyConstructor.setAccessible(true);
            Object key = technology.equals("ORACLE") ? null : keyConstructor.newInstance("\"ID\"", "bigint");
            Class<?> preparedClass = Class.forName(outer.getName() + "$PreparedStep");
            var preparedConstructor = preparedClass.getDeclaredConstructors()[0];
            preparedConstructor.setAccessible(true);
            Object prepared = preparedConstructor.newInstance(step, operation, 8000L, true, completed, key);
            var execute = outer.getDeclaredMethod("executeChunkedStep", preparedClass,
                    java.util.concurrent.ExecutorService.class, int.class);
            execute.setAccessible(true);
            var workers = Executors.newFixedThreadPool(2);
            try {
                require(((Long) execute.invoke(executor, prepared, workers, 2)) == 8000L,
                        "resume must include the previously committed rows");
            } finally { workers.shutdownNow(); }
            for (int number = 1; number <= 8; number++) {
                require(applications.get(number).get() == 1, "chunk " + number + " must be applied exactly once");
                require(chunks.get(number).status().equals("SUCCEEDED"), "resume must finish every chunk");
            }
            // Unknown running/cancelled rows remain blocked; only confirmed rollback cancellation is retryable.
            for (String status : List.of("RUNNING", "CANCELLED")) {
                chunks.put(7, new Chunk(status, "7000", 0, null));
                boolean blocked = false;
                try { OracleParallelPlanExecutor.requireReconciledChunks(repository, 90); }
                catch (IllegalStateException expected) { blocked = true; }
                require(blocked, "unconfirmed " + status + " must not be retried");
            }
            chunks.put(7, new Chunk("CANCELLED", "7000", 0, "PRIVONYM-ROLLBACK-CONFIRMED: Cancelled"));
            OracleParallelPlanExecutor.requireReconciledChunks(repository, 90);
        }
    }

    @Override public boolean acceptsURL(String url) { return url.equals(targetUrl) || url.equals("jdbc:audit:resume-repository"); }
    @Override public Connection connect(String url, Properties properties) {
        if (!acceptsURL(url)) return null;
        boolean target = url.equals(targetUrl);
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "setReadOnly", "commit", "rollback", "close" -> null;
                    case "prepareStatement" -> statement((String) args[0], target);
                    case "createStatement" -> Proxy.newProxyInstance(Statement.class.getClassLoader(),
                            new Class<?>[] {Statement.class}, (ignored, call, values) -> switch (call.getName()) {
                                case "execute" -> false;
                                case "close" -> null;
                                default -> throw new UnsupportedOperationException(call.getName());
                            });
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PreparedStatement statement(String sql, boolean target) {
        Map<Integer,Object> parameters = new HashMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "setLong", "setInt", "setString" -> { parameters.put((Integer) args[0], args[1]); yield null; }
                    case "setNull" -> { parameters.put((Integer) args[0], null); yield null; }
                    case "setQueryTimeout", "close" -> null;
                    case "executeQuery" -> {
                        List<Object[]> values = new ArrayList<>();
                        if (target) {
                            int previous = parameters.get(2) == null ? 0 : Integer.parseInt(parameters.get(2).toString());
                            if (previous < 8000) values.add(new Object[] {Integer.toString(previous + 1000)});
                        } else if (sql.contains("COUNT(*)")) {
                            require(sql.contains("COALESCE(error_message,'UNKNOWN')"), "NULL cancellation errors must remain blocked");
                            values.add(new Object[] {chunks.values().stream().filter(chunk ->
                                    !chunk.status().equals("SUCCEEDED") && !chunk.status().equals("FAILED")
                                    && !(chunk.status().equals("CANCELLED") && chunk.error() != null
                                    && chunk.error().startsWith("PRIVONYM-ROLLBACK-CONFIRMED:"))).count()});
                        } else if (sql.contains("SELECT chunk_number")) {
                            chunks.entrySet().stream().filter(entry -> entry.getValue().status().equals("SUCCEEDED"))
                                    .sorted(Map.Entry.comparingByKey()).forEach(entry -> values.add(new Object[] {
                                            entry.getKey(), entry.getValue().end(), entry.getValue().rows()}));
                        } else if (sql.contains("SELECT chunk_id")) {
                            values.add(new Object[] {((Number) parameters.get(2)).longValue()});
                        } else throw new UnsupportedOperationException(sql);
                        yield rows(values);
                    }
                    case "executeUpdate" -> {
                        if (target) {
                            int number = Integer.parseInt(parameters.get(3).toString()) / 1000;
                            applications.get(number).incrementAndGet();
                            yield 1000;
                        }
                        if (sql.stripLeading().startsWith("DELETE FROM job_chunk")) {
                            int number = ((Number) parameters.get(2)).intValue();
                            require(chunks.get(number).status().equals("FAILED"), "only failed ranges should be replaced");
                            chunks.remove(number);
                        } else if (sql.contains("INSERT INTO job_chunk")) {
                            int number = ((Number) parameters.get(2)).intValue();
                            require(chunks.putIfAbsent(number, new Chunk("RUNNING", parameters.get(4).toString(), 0, null)) == null,
                                    "a successful checkpoint must never be deleted");
                        } else if (sql.contains("UPDATE job_chunk SET status='SUCCEEDED'")) {
                            int number = ((Number) parameters.get(2)).intValue();
                            Chunk old = chunks.get(number);
                            chunks.put(number, new Chunk("SUCCEEDED", old.end(), ((Number) parameters.get(1)).longValue(), null));
                        }
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet rows(List<Object[]> values) {
        int[] position = {-1};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++position[0] < values.size();
                    case "getString" -> values.get(position[0])[(Integer) args[0] - 1].toString();
                    case "getLong" -> ((Number) values.get(position[0])[(Integer) args[0] - 1]).longValue();
                    case "getInt" -> ((Number) values.get(position[0])[(Integer) args[0] - 1]).intValue();
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
}
