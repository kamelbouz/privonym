package com.privonym.application.service;

import com.privonym.application.port.in.QueryMaskingInventoriesUseCase;
import com.privonym.application.port.out.MaskingInventoryQueryRepository;
import java.util.List;
import java.util.Objects;

public final class MaskingInventoryQueryService implements QueryMaskingInventoriesUseCase {
    private final MaskingInventoryQueryRepository repository;

    public MaskingInventoryQueryService(MaskingInventoryQueryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<MaskingInventorySummary> listInventories() {
        return repository.list();
    }

    @Override
    public MaskingInventoryDetails getInventory(long rulesetVersionId) {
        if (rulesetVersionId <= 0) throw new IllegalArgumentException("Invalid inventory version");
        return repository.get(rulesetVersionId);
    }
}
