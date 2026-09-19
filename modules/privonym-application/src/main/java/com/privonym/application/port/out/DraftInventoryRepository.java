package com.privonym.application.port.out;

import com.privonym.application.service.DraftInventoryResult;
import com.privonym.application.service.SqlInventoryPreview;

public interface DraftInventoryRepository {
    DraftInventoryResult create(long dataSourceId, String inventoryCode,
            String inventoryName, String sourceSql, SqlInventoryPreview preview,
            String createdBy);
}
