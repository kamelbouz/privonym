package com.privonym.adapter.oracle;

import java.util.Objects;

public record OracleQualifiedTable(String schema, String table) {
    public OracleQualifiedTable {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(table, "table");
        OracleIdentifier.quote(schema);
        OracleIdentifier.quote(table);
    }

    public String toSql() {
        return OracleIdentifier.quote(schema) + "." + OracleIdentifier.quote(table);
    }
}
