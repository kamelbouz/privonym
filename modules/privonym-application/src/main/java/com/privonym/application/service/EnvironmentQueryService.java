package com.privonym.application.service;

import com.privonym.application.port.in.QueryEnvironmentsUseCase;
import com.privonym.application.port.out.EnvironmentQueryRepository;
import java.util.List;
import java.util.Objects;
import java.util.Locale;

public final class EnvironmentQueryService implements QueryEnvironmentsUseCase {
    private final EnvironmentQueryRepository repository;

    public EnvironmentQueryService(EnvironmentQueryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<EnvironmentSummary> listEnvironments() {
        return List.copyOf(repository.list());
    }

    @Override
    public EnvironmentDetails getEnvironment(String code) {
        if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,29}")) {
            throw new IllegalArgumentException("Invalid environment code");
        }
        return repository.get(code);
    }

    @Override
    public EnvironmentSummary createEnvironment(String code, String name, String description) {
        String normalizedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String normalizedName = name == null ? "" : name.trim();
        String normalizedDescription = description == null ? "" : description.trim();
        if (!normalizedCode.matches("[A-Z][A-Z0-9_-]{1,29}")) {
            throw new IllegalArgumentException(
                    "Environment code must contain 2 to 30 letters, digits, hyphens or underscores");
        }
        if (normalizedName.isBlank() || normalizedName.length() > 120) {
            throw new IllegalArgumentException("Environment name must contain 1 to 120 characters");
        }
        if (normalizedDescription.length() > 1000) {
            throw new IllegalArgumentException("Environment description is limited to 1000 characters");
        }
        return repository.create(normalizedCode, normalizedName, normalizedDescription);
    }
}
