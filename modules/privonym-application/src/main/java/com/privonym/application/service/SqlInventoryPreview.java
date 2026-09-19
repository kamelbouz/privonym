package com.privonym.application.service;

import java.util.List;

public record SqlInventoryPreview(String technology, String checksum, int statementCount,
        List<SqlInventoryTable> tables, List<String> warnings) {
    public SqlInventoryPreview {
        tables = List.copyOf(tables);
        warnings = List.copyOf(warnings);
    }

    public int columnCount() {
        return tables.stream().mapToInt(table -> table.columns().size()).sum();
    }

    public int actionCount() {
        return tables.size() + columnCount();
    }
}
