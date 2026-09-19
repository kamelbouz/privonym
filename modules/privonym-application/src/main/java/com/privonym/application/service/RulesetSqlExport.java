package com.privonym.application.service;

import java.util.Objects;

public record RulesetSqlExport(String rulesetCode, int versionNumber, String technology,
        int statementCount, String sql) {
    public RulesetSqlExport {
        Objects.requireNonNull(rulesetCode, "rulesetCode");
        Objects.requireNonNull(technology, "technology");
        Objects.requireNonNull(sql, "sql");
        if (versionNumber < 1 || statementCount < 1 || sql.isBlank()) {
            throw new IllegalArgumentException("A SQL export requires a version and statements");
        }
    }
}
