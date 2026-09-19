package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.OracleMaskingAssignment;
import com.privonym.adapter.oracle.OracleQualifiedTable;

import java.util.List;
import java.util.Objects;

public record RepositoryTablePlan(
        long jobId,
        String jobReference,
        long catalogTableId,
        OracleQualifiedTable table,
        String tableAlgorithmCode,
        String filterExpression,
        List<OracleMaskingAssignment> assignments) {

    public RepositoryTablePlan {
        Objects.requireNonNull(jobReference, "jobReference");
        Objects.requireNonNull(table, "table");
        tableAlgorithmCode = tableAlgorithmCode == null ? "" : tableAlgorithmCode;
        filterExpression = filterExpression == null ? "" : filterExpression;
        assignments = List.copyOf(assignments);
        if (assignments.isEmpty() == tableAlgorithmCode.isBlank()) {
            throw new IllegalArgumentException(
                    "A table plan requires either column assignments or one table algorithm");
        }
    }
}
