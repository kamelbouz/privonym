package com.privonym.repository.oracle;

import com.privonym.adapter.oracle.AlgorithmParameter;
import com.privonym.adapter.oracle.ParameterType;

import java.math.BigDecimal;
import java.util.Set;

final class OracleParameterPolicy {
    private OracleParameterPolicy() {
    }

    static AlgorithmParameter create(
            String algorithmCode,
            String name,
            String databaseType,
            boolean required,
            String defaultValue) {
        String key = algorithmCode + "." + name;
        return switch (key) {
            case "MASK_PARTIAL.visible_prefix_length", "MASK_PARTIAL.visible_suffix_length" ->
                    AlgorithmParameter.integer(name, required, defaultValue, 0, 100);
            case "MASK_PARTIAL.mask_character" ->
                    AlgorithmParameter.enumeration(name, required, defaultValue, Set.of("*","#","X","x","?"));
            case "ROUND_NUMBER.scale" ->
                    AlgorithmParameter.integer(name, required, defaultValue, -6, 6);
            case "NUMBER_BUCKET.bucket_size" ->
                    AlgorithmParameter.decimal(name, required, defaultValue,
                            new BigDecimal("0.000001"), new BigDecimal("1000000000000"));
            case "MASK_STRING.mask_character" ->
                    AlgorithmParameter.string(name, required, defaultValue, 1);
            case "MASK_STRING.replacement_value" ->
                    AlgorithmParameter.string(name, required, defaultValue, 4000);
            case "MASK_PAYMENT_CARD.visible_suffix_length" ->
                    AlgorithmParameter.integer(name, required, defaultValue, 0, 8);
            case "GENERALIZE_DATE_YEAR.month_number" ->
                    AlgorithmParameter.integer(name, required, defaultValue, 1, 12);
            case "GENERALIZE_DATE_YEAR.day_number",
                 "GENERALIZE_DATE_MONTH.day_number" ->
                    AlgorithmParameter.integer(name, required, defaultValue, 1, 28);
            case "RANDOM_EMAIL_LOCAL_PART.local_part_length" ->
                    AlgorithmParameter.integer(name, required, defaultValue, 4, 64);
            case "RANDOM_PHONE_FR.prefix" ->
                    AlgorithmParameter.enumeration(
                            name, required, defaultValue, Set.of("06", "07"));
            case "PERTURB_NUMBER_PERCENT.max_ratio" ->
                    AlgorithmParameter.decimal(
                            name, required, defaultValue, BigDecimal.ZERO, BigDecimal.ONE);
            case "PERTURB_NUMBER_PERCENT.scale" ->
                    AlgorithmParameter.integer(name, required, defaultValue, 0, 18);
            case "HMAC_SHA256.key_material" ->
                    AlgorithmParameter.string(name, required, defaultValue, 512);
            case "IBAN_GENERATOR.key_material" ->
                    AlgorithmParameter.string(name, required, defaultValue, 512);
            case "RELATIONSHIP_AWARE_TOKEN.key_material" ->
                    AlgorithmParameter.string(name, required, defaultValue, 512);
            case "RELATIONSHIP_AWARE_TOKEN.token_namespace" ->
                    AlgorithmParameter.string(name, required, defaultValue, 128);
            case "DATE_SHIFT_DETERMINISTIC.shift_days" ->
                    AlgorithmParameter.integer(name, required, defaultValue, -36500, 36500);
            case "REGEX_MASK.pattern" ->
                    AlgorithmParameter.string(name, required, defaultValue, 500);
            case "REGEX_MASK.replacement",
                 "TEXT_REDACT_COMMON.replacement",
                 "TEXT_REDACT_EMAIL.replacement",
                 "TEXT_REDACT_PHONE_FR.replacement",
                 "TEXT_REDACT_PHONE_CH.replacement",
                 "TEXT_REDACT_IBAN_FR_CH.replacement" ->
                    AlgorithmParameter.string(name, required, defaultValue, 100);
            case "CONDITIONAL_MASK.condition_value",
                 "CONDITIONAL_MASK.replacement_value" ->
                    AlgorithmParameter.string(name, required, defaultValue, 1000);
            case "JSON_PATH_MASK.json_path" ->
                    AlgorithmParameter.string(name, required, defaultValue, 500);
            case "JSON_PATH_MASK.replacement_value" ->
                    AlgorithmParameter.string(name, required, defaultValue, 4000);
            default -> generic(name, databaseType, required, defaultValue);
        };
    }

    private static AlgorithmParameter generic(
            String name, String databaseType, boolean required, String defaultValue) {
        ParameterType type = ParameterType.valueOf(databaseType);
        return switch (type) {
            case STRING -> AlgorithmParameter.string(name, required, defaultValue, 4000);
            case INTEGER -> AlgorithmParameter.integer(
                    name, required, defaultValue, Long.MIN_VALUE, Long.MAX_VALUE);
            case DECIMAL -> AlgorithmParameter.decimal(
                    name, required, defaultValue, null, null);
            case BOOLEAN, DATE -> new AlgorithmParameter(
                    name, type, required, defaultValue, null, null, null, Set.of());
            case ENUM -> throw new IllegalArgumentException(
                    "No allow-list policy is registered for parameter: " + name);
        };
    }
}
