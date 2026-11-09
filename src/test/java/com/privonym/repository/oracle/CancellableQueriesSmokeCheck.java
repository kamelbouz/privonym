package com.privonym.repository.oracle;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class CancellableQueriesSmokeCheck {
    private CancellableQueriesSmokeCheck() { }

    static void verify() throws Exception {
        Set<Statement> tracked = ConcurrentHashMap.newKeySet();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        ResultSet deferred = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                    if (method.getName().equals("next")) {
                        reading.countDown();
                        if (!cancelled.await(3, TimeUnit.SECONDS))
                            throw new AssertionError("Deferred result was not cancelled");
                        throw new SQLException("Query cancelled", "HY008");
                    }
                    if (method.getName().equals("close")) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> deferred;
                    case "cancel" -> { cancelled.countDown(); yield null; }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var worker = Executors.newSingleThreadExecutor();
        try (ResultSet result = CancellableQueries.execute(statement, tracked::add,
                tracked::remove, () -> { })) {
            if (!tracked.contains(statement)) throw new AssertionError("Untracked before reading rows");
            var future = worker.submit(() -> {
                try { result.next(); throw new AssertionError("Expected cancellation"); }
                catch (SQLException expected) {
                    if (!"HY008".equals(expected.getSQLState())) throw new AssertionError(expected);
                }
                return true;
            });
            if (!reading.await(2, TimeUnit.SECONDS)) throw new AssertionError("Reader did not start");
            for (Statement active : tracked) active.cancel();
            if (!future.get(3, TimeUnit.SECONDS)) throw new AssertionError("Reader did not finish");
        } finally {
            cancelled.countDown();
            worker.shutdownNow();
        }
        if (!tracked.isEmpty()) throw new AssertionError("Closed result leaked tracking");
        try {
            CancellableQueries.execute(statement, tracked::add, tracked::remove,
                    () -> { throw new IllegalStateException("Already cancelled"); });
            throw new AssertionError("Cancelled execution was accepted");
        } catch (IllegalStateException expected) {
            if (!tracked.isEmpty()) throw new AssertionError("Rejected query leaked tracking");
        }
        System.out.println("Deferred JDBC result cancellation passed: next() remains cancellable until close.");
    }
}
