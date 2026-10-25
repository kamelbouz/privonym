package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.SqlChecksum;
import com.privonym.application.service.ExecutableStep;
import com.privonym.domain.job.PlanChecksum;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/** Fault injection at both sides of the target/journal commit boundary. */
final class CommitRecoverySmokeCheck implements Driver {
    private final int scenario;
    private int targetCommits;
    private int failureWrites;
    private int cancellationWrites;
    private OracleParallelPlanExecutor executor;

    private CommitRecoverySmokeCheck(int scenario) { this.scenario = scenario; }

    static void verify() throws Exception {
        List<Driver> original = Collections.list(DriverManager.getDrivers());
        for (Driver driver : original) DriverManager.deregisterDriver(driver);
        try {
            for (int scenario = 0; scenario < 6; scenario++) {
                CommitRecoverySmokeCheck driver = new CommitRecoverySmokeCheck(scenario);
                DriverManager.registerDriver(driver);
                try (OracleParallelPlanExecutor executor = new OracleParallelPlanExecutor(
                        "jdbc:audit:repository", "test", new char[0], "jdbc:postgresql:audit-target",
                        "test", new char[0], 1, "DIRECT", 1000, 1000)) {
                    driver.executor = executor;
                    String sql = "UPDATE demo SET value=1";
                    ExecutableStep step = new ExecutableStep(1, 10, sql, new PlanChecksum(SqlChecksum.sha256(sql)));
                    Class<?> outer = OracleParallelPlanExecutor.class;
                    var operationMethod = outer.getDeclaredMethod("operation", String.class);
                    operationMethod.setAccessible(true);
                    Object operation = operationMethod.invoke(null, sql);
                    Class<?> rangeType = Class.forName(outer.getName() + "$ChunkRange");
                    var rangeConstructor = rangeType.getDeclaredConstructor(int.class, String.class, String.class);
                    rangeConstructor.setAccessible(true);
                    Object range = rangeConstructor.newInstance(1, null, "DIRECT");
                    Class<?> workType = Class.forName(outer.getName() + "$Work");
                    var constructor = workType.getDeclaredConstructors()[0];
                    constructor.setAccessible(true);
                    Object work = constructor.newInstance(executor, step, operation, range, 1L, false, null);
                    var execute = workType.getDeclaredMethod("execute");
                    execute.setAccessible(true);
                    Throwable failure = null;
                    try { execute.invoke(work); }
                    catch (InvocationTargetException expected) {
                        failure = expected.getCause();
                    }
                    if (scenario <= 1) {
                        require(failure != null && failure.getMessage().contains("uncertain commit outcome")
                                && driver.targetCommits == 1 && driver.failureWrites == 0,
                                "commit uncertainty must not be marked retryable");
                    } else if (scenario == 2) {
                        require(failure != null && driver.targetCommits == 0 && driver.failureWrites == 1,
                                "failed DML with confirmed rollback must remain retryable");
                    } else if (scenario == 3) {
                        require(failure != null && failure.getMessage().contains("rollback was not confirmed")
                                && driver.targetCommits == 0 && driver.failureWrites == 0,
                                "failed rollback must not be marked retryable");
                    } else if (scenario == 4) {
                        require(failure instanceof com.privonym.application.service.JobExecutionCancelledException
                                && driver.targetCommits == 0 && driver.cancellationWrites == 1,
                                "confirmed cancellation must be journaled as retryable");
                    } else {
                        require(failure == null && driver.targetCommits == 1 && driver.failureWrites == 0,
                                "a commit confirmed during cancellation must preserve a successful checkpoint");
                    }
                } finally { DriverManager.deregisterDriver(driver); }
            }
        } finally {
            for (Driver driver : original) DriverManager.registerDriver(driver);
        }
        System.out.println("Commit/rollback/cancellation recovery fault injection passed.");
    }

    @Override public boolean acceptsURL(String url) { return url.equals("jdbc:audit:repository") || url.equals("jdbc:postgresql:audit-target"); }
    @Override public Connection connect(String url, Properties properties) {
        if (!acceptsURL(url)) return null;
        boolean target = url.contains("audit-target");
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "close" -> null;
                    case "rollback" -> {
                        if (target && scenario == 3) throw new SQLException("Simulated rollback connection failure");
                        yield null;
                    }
                    case "commit" -> {
                        if (target) {
                            targetCommits++;
                            if (scenario == 0) throw new SQLException("Simulated target connection lost during commit");
                            if (scenario == 5) requestCancellation();
                        }
                        yield null;
                    }
                    case "prepareStatement" -> statement((String) args[0], target);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PreparedStatement statement(String sql, boolean target) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "setLong", "setInt", "setString", "setNull", "setQueryTimeout", "close" -> null;
                    case "executeUpdate" -> {
                        if (target && scenario >= 2 && scenario <= 4) {
                            if (scenario == 4) requestCancellation();
                            throw new SQLException("Simulated DML interruption before commit");
                        }
                        if (!target && sql.stripLeading().startsWith("UPDATE")
                                && sql.contains("status='FAILED'")) failureWrites++;
                        if (!target && sql.stripLeading().startsWith("UPDATE") && sql.contains("status='CANCELLED'")) {
                            require(sql.contains("PRIVONYM-ROLLBACK-CONFIRMED:"), "cancellation needs explicit rollback confirmation");
                            cancellationWrites++;
                        }
                        if (!target && sql.contains("status='SUCCEEDED'")) {
                            require(sql.contains("status IN ('RUNNING','CANCELLED')"), "confirmed commit must survive cancellation race");
                            if (scenario == 1) throw new SQLException("Simulated journal unavailable after target commit");
                        }
                        yield 1;
                    }
                    case "executeQuery" -> Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                            new Class<?>[] {ResultSet.class}, (row, getter, values) -> switch (getter.getName()) {
                                case "next" -> true;
                                case "getLong" -> 1L;
                                case "close" -> null;
                                default -> throw new UnsupportedOperationException(getter.getName());
                            });
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
    private void requestCancellation() throws ReflectiveOperationException {
        var field = OracleParallelPlanExecutor.class.getDeclaredField("cancellationRequested");
        field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) field.get(executor)).set(true);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
}
