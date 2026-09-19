package com.privonym.application.service;

public record MaskingInventoryMapping(long mappingId, int statementNumber,
        int executionOrder, String targetType, String operation, String schema,
        String table, String column, String algorithmCode, String matchStatus,
        String diagnostic, String parametersJson, String filterExpression,
        String sourceExpression, String instanceName, String databaseName,
        String dataType, boolean nullable, boolean enabled, String algorithmType) {
}
