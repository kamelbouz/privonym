package com.privonym.application.port.out;

import com.privonym.application.service.DraftInventoryResult;
import com.privonym.application.service.SqlInventoryPreview;

public interface DraftInventoryRepository {
    DraftInventoryResult create(long dataSourceId, String inventoryCode,
            String inventoryName, String sourceSql, SqlInventoryPreview preview,
            String createdBy);
    default DraftInventoryResult createAuthorized(long dataSourceId,String inventoryCode,
            String inventoryName,String sourceSql,SqlInventoryPreview preview,String createdBy,long expectedRulesetId,long expectedOwnerUserId) {
        throw new UnsupportedOperationException("Authorized import is unavailable");
    }
}
