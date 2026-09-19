package com.privonym.application.port.out;

import com.privonym.application.service.MaskingInventoryDetails;
import com.privonym.application.service.MaskingInventorySummary;
import java.util.List;

public interface MaskingInventoryQueryRepository {
    List<MaskingInventorySummary> list();
    MaskingInventoryDetails get(long rulesetVersionId);
}
