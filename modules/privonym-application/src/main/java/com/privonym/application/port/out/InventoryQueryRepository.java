package com.privonym.application.port.out;

import com.privonym.application.service.DataSourceInventory;

public interface InventoryQueryRepository {
    DataSourceInventory getInventory(long dataSourceId);
}
