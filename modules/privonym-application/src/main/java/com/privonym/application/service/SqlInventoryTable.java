package com.privonym.application.service;

import java.util.List;

public record SqlInventoryTable(String schema, String name, String operation,
        String algorithmCode, String filterExpression, List<SqlInventoryColumn> columns) {
    public SqlInventoryTable {
        columns = List.copyOf(columns);
    }
}
