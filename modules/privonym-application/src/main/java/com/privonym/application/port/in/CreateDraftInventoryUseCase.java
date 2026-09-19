package com.privonym.application.port.in;

import com.privonym.application.service.DraftInventoryResult;
import com.privonym.application.service.SqlInventoryPreview;

public interface CreateDraftInventoryUseCase {
    DraftInventoryResult create(long dataSourceId, String inventoryCode,
            String inventoryName, String sql);

    default DraftInventoryResult create(long dataSourceId, String technology,
            String inventoryCode, String inventoryName, String sql) {
        return create(dataSourceId, inventoryCode, inventoryName, sql);
    }

    default DraftInventoryResult createFromInventory(long dataSourceId, String inventoryCode,
            String inventoryName, String source, SqlInventoryPreview preview) {
        throw new UnsupportedOperationException("CSV inventory import is not configured");
    }
}
