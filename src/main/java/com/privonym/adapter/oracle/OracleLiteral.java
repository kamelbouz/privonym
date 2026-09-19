package com.privonym.adapter.oracle;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

final class OracleLiteral {
    private OracleLiteral() {
    }

    static String render(AlgorithmParameter definition, String rawValue) {
        String value = rawValue != null ? rawValue : definition.defaultValue();
        if (value == null) {
            if (definition.required()) {
                throw new OracleTemplateException(
                        "Required parameter is missing: " + definition.name());
            }
            throw new OracleTemplateException(
                    "Parameter has no supplied or default value: " + definition.name());
        }

        return switch (definition.type()) {
            case STRING -> stringLiteral(definition, value);
            case ENUM -> enumLiteral(definition, value);
            case INTEGER -> integerLiteral(definition, value);
            case DECIMAL -> decimalLiteral(definition, value);
            case BOOLEAN -> booleanLiteral(definition, value);
            case DATE -> dateLiteral(definition, value);
        };
    }

    private static String stringLiteral(AlgorithmParameter definition, String value) {
        if (definition.maximumLength() != null
                && value.length() > definition.maximumLength()) {
            throw new OracleTemplateException(
                    "Parameter exceeds maximum length: " + definition.name());
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static String enumLiteral(AlgorithmParameter definition, String value) {
        if (!definition.allowedValues().contains(value)) {
            throw new OracleTemplateException(
                    "Value is not allowed for parameter " + definition.name() + ": " + value);
        }
        return stringLiteral(definition, value);
    }

    private static String integerLiteral(AlgorithmParameter definition, String value) {
        try {
            BigInteger parsed = new BigInteger(value);
            validateRange(definition, new BigDecimal(parsed));
            return parsed.toString();
        } catch (NumberFormatException exception) {
            throw new OracleTemplateException(
                    "Invalid integer for parameter " + definition.name(), exception);
        }
    }

    private static String decimalLiteral(AlgorithmParameter definition, String value) {
        try {
            BigDecimal parsed = new BigDecimal(value);
            validateRange(definition, parsed);
            return parsed.toPlainString();
        } catch (NumberFormatException exception) {
            throw new OracleTemplateException(
                    "Invalid decimal for parameter " + definition.name(), exception);
        }
    }

    private static String booleanLiteral(AlgorithmParameter definition, String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "y" -> "1";
            case "false", "0", "n" -> "0";
            default -> throw new OracleTemplateException(
                    "Invalid boolean for parameter " + definition.name());
        };
    }

    private static String dateLiteral(AlgorithmParameter definition, String value) {
        try {
            return "DATE '" + LocalDate.parse(value) + "'";
        } catch (DateTimeParseException exception) {
            throw new OracleTemplateException(
                    "Invalid ISO date for parameter " + definition.name(), exception);
        }
    }

    private static void validateRange(AlgorithmParameter definition, BigDecimal value) {
        if (definition.minimum() != null && value.compareTo(definition.minimum()) < 0) {
            throw new OracleTemplateException(
                    "Parameter is below minimum: " + definition.name());
        }
        if (definition.maximum() != null && value.compareTo(definition.maximum()) > 0) {
            throw new OracleTemplateException(
                    "Parameter exceeds maximum: " + definition.name());
        }
    }
}
