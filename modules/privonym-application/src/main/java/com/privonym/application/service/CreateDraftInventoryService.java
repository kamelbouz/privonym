package com.privonym.application.service;

import com.privonym.application.port.in.CreateDraftInventoryUseCase;
import com.privonym.application.port.in.PreviewSqlInventoryUseCase;
import com.privonym.application.port.out.DraftInventoryRepository;
import java.util.Locale;
import java.util.Objects;

public final class CreateDraftInventoryService implements CreateDraftInventoryUseCase {
    private final PreviewSqlInventoryUseCase previewer;
    private final DraftInventoryRepository repository;
    private final String createdBy;

    public CreateDraftInventoryService(PreviewSqlInventoryUseCase previewer,
            DraftInventoryRepository repository, String createdBy) {
        this.previewer = Objects.requireNonNull(previewer, "previewer");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    }

    @Override
    public DraftInventoryResult create(long dataSourceId, String inventoryCode,
            String inventoryName, String sql) {
        return create(dataSourceId, "ORACLE", inventoryCode, inventoryName, sql);
    }

    @Override
    public DraftInventoryResult create(long dataSourceId, String technology,
            String inventoryCode, String inventoryName, String sql) {
        return createFromInventory(dataSourceId, inventoryCode, inventoryName, sql,
                previewer.preview(technology, sql));
    }

    @Override
    public DraftInventoryResult createFromInventory(long dataSourceId, String inventoryCode,
            String inventoryName, String source, SqlInventoryPreview preview) {
        if (dataSourceId <= 0) throw new IllegalArgumentException("A valid data source is required");
        String code = inventoryCode == null ? ""
                : inventoryCode.trim().toUpperCase(Locale.ROOT);
        String name = inventoryName == null ? "" : inventoryName.trim();
        if (preview.tables().isEmpty()) {
            throw new IllegalArgumentException("No masking operation is available for the draft inventory");
        }
        return repository.create(dataSourceId, code, name, source, preview, createdBy);
    }
}
