package com.privonym.repository;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** Repository connections only. Target SQL and target connections never pass through this class. */
public final class RepositoryConnections {
    private RepositoryConnections() { }

    public static Connection open(String url, String user, String password) throws SQLException {
        Connection physical = DriverManager.getConnection(url, user, password);
        if (!url.startsWith("jdbc:postgresql:")) return physical;
        try (Statement session = physical.createStatement()) {
            session.execute("SET TIME ZONE 'UTC'");
            session.execute("SET application_name='privonym-repository'");
        } catch (SQLException error) {
            physical.close();
            throw error;
        }
        return (Connection) Proxy.newProxyInstance(RepositoryConnections.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    Object[] args = arguments == null ? null : arguments.clone();
                    if (args != null && args.length > 0 && args[0] instanceof String sql
                            && (method.getName().equals("prepareStatement")
                                || method.getName().equals("prepareCall"))) {
                        args[0] = PostgresqlRepositorySql.render(sql);
                        if (args.length == 2 && args[1] instanceof String[] columns) {
                            args[1] = java.util.Arrays.stream(columns)
                                    .map(column -> column.toLowerCase(java.util.Locale.ROOT)).toArray(String[]::new);
                        }
                    }
                    Object result = invoke(physical, method, args);
                    if (result instanceof Statement statement) {
                        return wrap(statement, (Connection) proxy);
                    }
                    return result;
                });
    }

    private static Statement wrap(Statement physical, Connection connection) {
        Class<?> contract = physical instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(RepositoryConnections.class.getClassLoader(),
                new Class<?>[]{contract}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) return connection;
                    Object[] args = arguments == null ? null : arguments.clone();
                    if (args != null && args.length > 0 && args[0] instanceof String sql
                            && (method.getName().startsWith("execute") || method.getName().equals("addBatch"))) {
                        args[0] = PostgresqlRepositorySql.render(sql);
                    }
                    // PostgreSQL text columns are not large-object OIDs.
                    if (method.getName().equals("setNull") && (int) args[1] == java.sql.Types.CLOB) {
                        args[1] = java.sql.Types.VARCHAR;
                    }
                    return invoke(physical, method, args);
                });
    }

    private static Object invoke(Object receiver, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof SQLException sql) {
                int legacyCode = switch (String.valueOf(sql.getSQLState())) {
                    case "23505" -> 1;
                    case "55P03" -> 54;
                    case "42P01" -> 942;
                    case "23503" -> 2292;
                    default -> sql.getErrorCode();
                };
                if (legacyCode != sql.getErrorCode()) {
                    throw new SQLException(sql.getMessage(), sql.getSQLState(), legacyCode, sql);
                }
            }
            throw cause;
        }
    }
}
