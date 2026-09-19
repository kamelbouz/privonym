package com.privonym.adapter.oracle;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

public record AlgorithmParameter(
        String name,
        ParameterType type,
        boolean required,
        String defaultValue,
        BigDecimal minimum,
        BigDecimal maximum,
        Integer maximumLength,
        Set<String> allowedValues) {

    public AlgorithmParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (!name.matches("[a-z][a-z0-9_]{0,99}")) {
            throw new IllegalArgumentException("Invalid parameter name: " + name);
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
        if (maximumLength != null && maximumLength < 1) {
            throw new IllegalArgumentException("maximumLength must be positive");
        }
        allowedValues = allowedValues == null ? Set.of() : Set.copyOf(allowedValues);
    }

    public static AlgorithmParameter string(
            String name, boolean required, String defaultValue, int maximumLength) {
        return new AlgorithmParameter(
                name, ParameterType.STRING, required, defaultValue,
                null, null, maximumLength, Set.of());
    }

    public static AlgorithmParameter integer(
            String name, boolean required, String defaultValue, long minimum, long maximum) {
        return new AlgorithmParameter(
                name, ParameterType.INTEGER, required, defaultValue,
                BigDecimal.valueOf(minimum), BigDecimal.valueOf(maximum), null, Set.of());
    }

    public static AlgorithmParameter decimal(
            String name, boolean required, String defaultValue,
            BigDecimal minimum, BigDecimal maximum) {
        return new AlgorithmParameter(
                name, ParameterType.DECIMAL, required, defaultValue,
                minimum, maximum, null, Set.of());
    }

    public static AlgorithmParameter enumeration(
            String name, boolean required, String defaultValue, Set<String> allowedValues) {
        return new AlgorithmParameter(
                name, ParameterType.ENUM, required, defaultValue,
                null, null, null, allowedValues);
    }
}
