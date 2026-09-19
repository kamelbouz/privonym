package com.privonym.application.service;

import java.util.List;

public final class RulesetInventoryCsv {
    public static final String HEADER =
            "RULESET_NAME,TABLE_OWNER,TABLE_NAME,COLUMN_NAME,DATA_TYPE,ALGORITHM,IS_MASKED,CRITERIA\r\n";

    private RulesetInventoryCsv() {
    }

    public static String export(RulesetDetails details) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (RulesetInventoryEntry entry : details.inventory()) {
            append(csv, List.of(details.ruleset().code(), entry.tableOwner(), entry.tableName(),
                    entry.columnName(), entry.dataType(), entry.algorithm(),
                    entry.masked() ? "Y" : "N",
                    entry.criteria().isBlank() ? "ALL" : entry.criteria()));
        }
        return csv.toString();
    }

    private static void append(StringBuilder csv, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) csv.append(',');
            csv.append('"').append(values.get(index).replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
