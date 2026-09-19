package com.privonym.application.service;

public record RulesetJobView(long id, String reference, String status, int versionNumber,
        String createdAt, String plannedAt) {
}
