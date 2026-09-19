package com.privonym.application.service;

public record RulesetVersionView(long id, int number, String status,
        String createdAt, String publishedAt, int inventoryCount, int jobCount) {
}
