package com.privonym.application.service;

public record InventoryMutationResult(long rulesetVersionId, String status,
        int reviewItems, String message) {
}
