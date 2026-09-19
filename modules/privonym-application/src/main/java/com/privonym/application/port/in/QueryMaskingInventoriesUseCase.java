package com.privonym.application.port.in;

import com.privonym.application.service.MaskingInventoryDetails;
import com.privonym.application.service.MaskingInventorySummary;
import java.util.List;

public interface QueryMaskingInventoriesUseCase {
    List<MaskingInventorySummary> listInventories();
    MaskingInventoryDetails getInventory(long rulesetVersionId);
}
