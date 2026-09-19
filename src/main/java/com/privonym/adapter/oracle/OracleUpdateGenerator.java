package com.privonym.adapter.oracle;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Generates one auditable UPDATE statement for one catalogued Oracle table. */
public final class OracleUpdateGenerator {
    private final OracleTemplateRenderer renderer;

    public OracleUpdateGenerator() {
        this(new OracleTemplateRenderer());
    }

    OracleUpdateGenerator(OracleTemplateRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public GeneratedOracleStatement generate(
            OracleQualifiedTable table,
            List<OracleMaskingAssignment> assignments) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(assignments, "assignments");
        if (assignments.isEmpty()) {
            throw new OracleTemplateException("At least one masking assignment is required");
        }

        List<OracleMaskingAssignment> ordered = assignments.stream()
                .sorted(Comparator.comparingInt(OracleMaskingAssignment::executionOrder)
                        .thenComparing(OracleMaskingAssignment::columnName))
                .toList();

        Set<String> columns = new HashSet<>();
        StringBuilder sql = new StringBuilder(256);
        sql.append("UPDATE ").append(table.toSql()).append(System.lineSeparator());
        sql.append("SET").append(System.lineSeparator());

        for (int index = 0; index < ordered.size(); index++) {
            OracleMaskingAssignment assignment = ordered.get(index);
            String normalizedColumn = assignment.columnName().toUpperCase(java.util.Locale.ROOT);
            if (!columns.add(normalizedColumn)) {
                throw new OracleTemplateException(
                        "Duplicate masking assignment for column: " + assignment.columnName());
            }

            String expression = renderer.render(
                    assignment.template(),
                    assignment.columnName(),
                    table,
                    assignment.parameterDefinitions(),
                    assignment.parameterValues());

            sql.append("    ")
                    .append(OracleIdentifier.quote(assignment.columnName()))
                    .append(" = ")
                    .append(expression);
            if (index < ordered.size() - 1) {
                sql.append(',');
            }
            sql.append(System.lineSeparator());
        }

        String generatedSql = sql.toString().stripTrailing();
        return new GeneratedOracleStatement(generatedSql, SqlChecksum.sha256(generatedSql));
    }
}
