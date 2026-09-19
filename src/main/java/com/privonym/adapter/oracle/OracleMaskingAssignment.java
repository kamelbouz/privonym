package com.privonym.adapter.oracle;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record OracleMaskingAssignment(
        String columnName,
        String algorithmCode,
        String implementationType,
        String template,
        int executionOrder,
        List<AlgorithmParameter> parameterDefinitions,
        Map<String, String> parameterValues,
        Long lookupDatasetId,
        List<String> lookupValues) {

    public OracleMaskingAssignment(
            String columnName,
            String algorithmCode,
            String implementationType,
            String template,
            int executionOrder,
            List<AlgorithmParameter> parameterDefinitions,
            Map<String, String> parameterValues) {
        this(columnName, algorithmCode, implementationType, template, executionOrder,
                parameterDefinitions, parameterValues, null, List.of());
    }

    public OracleMaskingAssignment {
        Objects.requireNonNull(columnName, "columnName");
        Objects.requireNonNull(algorithmCode, "algorithmCode");
        Objects.requireNonNull(implementationType, "implementationType");
        Objects.requireNonNull(template, "template");
        parameterDefinitions = List.copyOf(parameterDefinitions);
        parameterValues = Map.copyOf(parameterValues);
        lookupValues = List.copyOf(lookupValues == null ? List.of() : lookupValues);
        OracleIdentifier.quote(columnName);
        if (!algorithmCode.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw new OracleTemplateException("Invalid algorithm code: " + algorithmCode);
        }
        if (!"SQL_EXPRESSION".equals(implementationType)
                && !"LOOKUP_MAPPING".equals(implementationType)) {
            throw new OracleTemplateException(
                    "An UPDATE assignment requires SQL_EXPRESSION or LOOKUP_MAPPING, received: "
                            + implementationType);
        }
        if (executionOrder < 0) {
            throw new OracleTemplateException("executionOrder must not be negative");
        }
    }
}
