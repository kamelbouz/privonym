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
    private final boolean failTargetCommit;
    private int targetCommits;
    private int failureWrites;

    private CommitRecoverySmokeCheck(boolean failTargetCommit) { this.failTargetCommit = failTargetCommit; }

    static void verify() throws Exception {
        List<Driver> original = Collections.list(DriverManager.getDrivers());
        for (Driver driver : original) DriverManager.deregisterDriver(driver);
        try {
            for (boolean failTarget : new boolean[] {true, false}) {
                CommitRecoverySmokeCheck driver = new CommitRecoverySmokeCheck(failTarget);
                DriverManager.registerDriver(driver);
                try (OracleParallelPlanExecutor executor = new OracleParallelPlanExecutor(
                        "jdbc:audit:repository", "test", new char[0], "jdbc:postgresql:audit-target",
                        "test", new char[0], 1, "DIRECT", 1000, 1000)) {
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
                    boolean blocked = false;
                    try { execute.invoke(work); }
                    catch (InvocationTargetException expected) {
                        blocked = expected.getCause().getMessage().contains("uncertain commit outcome");
                        if (!blocked) throw new IllegalStateException("Unexpected worker failure", expected.getCause());
                    }
                    if (!blocked || driver.targetCommits != 1 || driver.failureWrites != 0) {
                        throw new IllegalStateException("Uncertain commit must remain unreconciled; blocked="
                                + blocked + ", targetCommits=" + driver.targetCommits
                                + ", failureWrites=" + driver.failureWrites);
                    }
                } finally { DriverManager.deregisterDriver(driver); }
            }
        } finally {
            for (Driver driver : original) DriverManager.registerDriver(driver);
        }
        System.out.println("Commit recovery fault injection passed.");
    }

    @Override public boolean acceptsURL(String url) { return url.equals("jdbc:audit:repository") || url.equals("jdbc:postgresql:audit-target"); }
    @Override public Connection connect(String url, Properties properties) {
        if (!acceptsURL(url)) return null;
        boolean target = url.contains("audit-target");
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "rollback", "close" -> null;
                    case "commit" -> {
                        if (target) {
                            targetCommits++;
                            if (failTargetCommit) throw new SQLException("Simulated target connection lost during commit");
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
                        if (!target && sql.stripLeading().startsWith("UPDATE")
                                && sql.contains("status='FAILED'")) failureWrites++;
                        if (!target && sql.contains("status='SUCCEEDED'")) throw new SQLException("Simulated journal unavailable after target commit");
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
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
}
