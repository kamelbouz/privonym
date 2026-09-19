package com.privonym.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** CSV representation of the column inventory owned by one ruleset. */
public final class LegacyInventoryCsv {
    public static final List<String> COLUMNS = List.of("RULESET_NAME", "TABLE_OWNER",
            "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "ALGORITHM", "IS_MASKED", "CRITERIA");

    private LegacyInventoryCsv() {
    }

    public static String template() {
        return String.join(",", COLUMNS) + "\r\n";
    }

    public static String rulesetName(String csv) {
        List<Map<String, String>> rows = rows(csv);
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV contains no inventory entry");
        String name = required(rows.get(0), "RULESET_NAME", 2).toUpperCase(Locale.ROOT);
        for (int index = 1; index < rows.size(); index++) {
            if (!name.equalsIgnoreCase(required(rows.get(index), "RULESET_NAME", index + 2))) {
                throw new IllegalArgumentException("A CSV file can contain only one RULESET_NAME");
            }
        }
        if (!name.matches("[A-Z][A-Z0-9_-]{2,99}")) {
            throw new IllegalArgumentException(
                    "RULESET_NAME must contain 3 to 100 letters, digits, hyphens or underscores");
        }
        return name;
    }

    public static String export(MaskingInventoryDetails details) {
        StringBuilder csv = new StringBuilder(template());
        for (MaskingInventoryMapping item : details.mappings()) {
            if (!"COLUMN".equals(item.targetType())) continue;
            appendRow(csv, List.of(details.inventory().code(), item.schema(), item.table(),
                    item.column(), item.dataType(), item.algorithmCode(),
                    item.enabled() ? "Y" : "N", blankAs(item.filterExpression(), "ALL")));
        }
        return csv.toString();
    }

    public static SqlInventoryPreview parse(String csv) {
        List<Map<String, String>> rows = rows(csv);
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV contains no inventory entry");
        rulesetName(csv);
        Map<String, TableBuilder> tables = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, String> row = rows.get(index);
            int rowNumber = index + 2;
            String schema = required(row, "TABLE_OWNER", rowNumber).toUpperCase(Locale.ROOT);
            String table = required(row, "TABLE_NAME", rowNumber).toUpperCase(Locale.ROOT);
            String column = required(row, "COLUMN_NAME", rowNumber).toUpperCase(Locale.ROOT);
            String algorithm = required(row, "ALGORITHM", rowNumber).toUpperCase(Locale.ROOT);
            String masked = blankAs(row.get("IS_MASKED"), "Y").toUpperCase(Locale.ROOT);
            if (!masked.matches("Y|N")) {
                throw new IllegalArgumentException("CSV row " + rowNumber + " requires IS_MASKED Y or N");
            }
            String filter = criteria(row.get("CRITERIA"));
            String key = schema + '\u0000' + table + '\u0000' + filter;
            TableBuilder target = tables.computeIfAbsent(key,
                    ignored -> new TableBuilder(schema, table, filter));
            target.columns.add(new SqlInventoryColumn(column, target.columns.size() + 1, "",
                    algorithm, Map.of(), "MATCHED", "Y".equals(masked)));
        }
        return new SqlInventoryPreview("ORACLE", sha256(csv), rows.size(),
                tables.values().stream().map(TableBuilder::freeze).toList(),
                List.of());
    }

    private static List<Map<String, String>> rows(String csv) {
        if (csv == null || csv.isBlank()) throw new IllegalArgumentException("CSV file is empty");
        List<List<String>> records = records(csv);
        if (records.isEmpty()) throw new IllegalArgumentException("CSV file is empty");
        List<String> header = records.remove(0).stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).toList();
        if (!header.equals(COLUMNS)) {
            throw new IllegalArgumentException("CSV header must be: " + String.join(",", COLUMNS));
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int record = 0; record < records.size(); record++) {
            List<String> values = records.get(record);
            if (values.stream().allMatch(String::isBlank)) continue;
            if (values.size() != COLUMNS.size()) {
                throw new IllegalArgumentException("CSV row " + (record + 2) + " contains "
                        + values.size() + " fields instead of " + COLUMNS.size());
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int index = 0; index < COLUMNS.size(); index++) {
                row.put(COLUMNS.get(index), values.get(index).trim());
            }
            rows.add(row);
        }
        return rows;
    }

    private static String required(Map<String, String> row, String key, int rowNumber) {
        String value = row.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CSV row " + rowNumber + " requires " + key);
        }
        return value;
    }

    private static String criteria(String value) {
        return value == null || value.isBlank() || "ALL".equalsIgnoreCase(value) ? "" : value;
    }

    private static List<List<String>> records(String csv) {
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < csv.length(); index++) {
            char character = csv.charAt(index);
            if (quoted) {
                if (character == '"' && index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
                    field.append('"'); index++;
                } else if (character == '"') quoted = false;
                else field.append(character);
            } else if (character == '"' && field.length() == 0) quoted = true;
            else if (character == ',') { row.add(field.toString()); field.setLength(0); }
            else if (character == '\r' || character == '\n') {
                if (character == '\r' && index + 1 < csv.length() && csv.charAt(index + 1) == '\n') index++;
                row.add(field.toString()); field.setLength(0); records.add(row); row = new ArrayList<>();
            } else field.append(character);
        }
        if (quoted) throw new IllegalArgumentException("CSV contains an unterminated quoted field");
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); records.add(row); }
        if (!records.isEmpty() && !records.get(0).isEmpty()) {
            records.get(0).set(0, records.get(0).get(0).replace("\uFEFF", ""));
        }
        return records;
    }

    private static void appendRow(StringBuilder csv, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) csv.append(',');
            String value = values.get(index) == null ? "" : values.get(index);
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    private static String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static final class TableBuilder {
        private final String schema;
        private final String table;
        private final String filter;
        private final List<SqlInventoryColumn> columns = new ArrayList<>();

        private TableBuilder(String schema, String table, String filter) {
            this.schema = schema; this.table = table; this.filter = filter;
        }

        private SqlInventoryTable freeze() {
            return new SqlInventoryTable(schema, table, "UPDATE", "", filter, columns);
        }
    }
}
