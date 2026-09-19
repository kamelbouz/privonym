package com.privonym.repository.oracle;

import java.util.Objects;

public record OracleConnectionDetails(
        String technologyCode, String host, int port, String serviceName,
        String databaseName, String username) {
    public OracleConnectionDetails {
        Objects.requireNonNull(technologyCode, "technologyCode");
        Objects.requireNonNull(host, "host");
        serviceName = serviceName == null ? "" : serviceName;
        databaseName = databaseName == null ? "" : databaseName;
        Objects.requireNonNull(username, "username");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid Oracle port: " + port);
        }
    }

    public String jdbcUrl() {
        return switch (technologyCode) {
            case "ORACLE" -> "jdbc:oracle:thin:@//" + host + ":" + port + "/"
                    + (serviceName.isBlank() ? databaseName : serviceName);
            case "POSTGRESQL" -> "jdbc:postgresql://" + host + ":" + port + "/"
                    + (databaseName.isBlank() ? serviceName : databaseName);
            case "SQLSERVER" -> "jdbc:sqlserver://" + host + ":" + port
                    + ";databaseName=" + (databaseName.isBlank() ? serviceName : databaseName)
                    + ";encrypt=true;trustServerCertificate=true;loginTimeout=15";
            default -> throw new IllegalStateException(
                    "Unsupported target technology: " + technologyCode);
        };
    }
}
