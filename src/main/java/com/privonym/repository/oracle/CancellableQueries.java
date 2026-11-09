package com.privonym.repository.oracle;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Consumer;

/** JDBC drivers may defer waiting for server results until ResultSet.next(). */
final class CancellableQueries {
    private CancellableQueries() { }

    static ResultSet execute(PreparedStatement statement, Consumer<Statement> register,
            Consumer<Statement> unregister, Runnable check) throws SQLException {
        try {
            register.accept(statement);
            check.run();
            ResultSet result = statement.executeQuery();
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                        try {
                            return method.invoke(result, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        } finally {
                            if (method.getName().equals("close")) unregister.accept(statement);
                        }
                    });
        } catch (SQLException | RuntimeException failure) {
            unregister.accept(statement);
            throw failure;
        }
    }
}
