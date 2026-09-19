package com.privonym.application.service;

import com.privonym.application.port.in.QueryInventoryUseCase;
import com.privonym.application.port.out.InventoryQueryRepository;
import java.util.Objects;

public final class InventoryQueryService implements QueryInventoryUseCase {
    private final InventoryQueryRepository repository;

    public InventoryQueryService(InventoryQueryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public DataSourceInventory getInventory(long dataSourceId) {
        if (dataSourceId < 1) {
            throw new IllegalArgumentException("Data source id must be positive");
        }
        return repository.getInventory(dataSourceId);
    }
}
