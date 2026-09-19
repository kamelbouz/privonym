package com.privonym.application.port.in;

import com.privonym.application.service.DataSourceInventory;

public interface QueryInventoryUseCase {
    DataSourceInventory getInventory(long dataSourceId);
}
