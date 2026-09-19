package com.privonym.application.service;

import java.util.List;

public record MaskingAlgorithmOption(String code, String name, String category,
        String versionStatus, List<String> parameters, String implementationType) {
    public MaskingAlgorithmOption(String code, String name, String category,
            String versionStatus, List<String> parameters) {
        this(code, name, category, versionStatus, parameters, "SQL_EXPRESSION");
    }
    public MaskingAlgorithmOption {
        parameters = List.copyOf(parameters);
    }
}
