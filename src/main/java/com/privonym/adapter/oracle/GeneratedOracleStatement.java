package com.privonym.adapter.oracle;

import java.util.Objects;

public record GeneratedOracleStatement(String sql, String sha256) {
    public GeneratedOracleStatement {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 checksum");
        }
    }
}
