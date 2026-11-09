package com.privonym.application.service;

import java.util.List;

public record SqlInventoryPreview(String technology, String checksum, int statementCount,
        List<SqlInventoryTable> tables, List<String> warnings, String preScript,String postScript) {
    public SqlInventoryPreview(String technology,String checksum,int statementCount,
            List<SqlInventoryTable> tables,List<String> warnings) {
        this(technology,checksum,statementCount,tables,warnings,"","");
    }
    public SqlInventoryPreview {
        tables = List.copyOf(tables);
        warnings = List.copyOf(warnings);
        preScript = preScript == null ? "" : preScript;
        postScript = postScript == null ? "" : postScript;
    }

    public int columnCount() {
        return tables.stream().mapToInt(table -> table.columns().size()).sum();
    }

    public int actionCount() {
        return tables.size() + columnCount();
    }
}
