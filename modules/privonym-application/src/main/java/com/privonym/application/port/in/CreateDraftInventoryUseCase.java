package com.privonym.application.port.in;

import com.privonym.application.service.DraftInventoryResult;
import com.privonym.application.service.SqlInventoryPreview;

public interface CreateDraftInventoryUseCase {
    default DraftInventoryResult createAuthorized(long dataSourceId,String technology,String code,
            String name,String sql,long expectedRulesetId,long expectedOwnerUserId) {
        throw new UnsupportedOperationException("Authorized import is unavailable");
    }
    default DraftInventoryResult createFromInventoryAuthorized(long dataSourceId,String code,
            String name,String source,SqlInventoryPreview preview,long expectedRulesetId,long expectedOwnerUserId) {
        throw new UnsupportedOperationException("Authorized import is unavailable");
    }
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
