package com.privonym.application.service;

import java.util.List;

public record MaskingInventoryDetails(MaskingInventorySummary inventory,
        List<MaskingInventoryMapping> mappings) {
    public MaskingInventoryDetails {
        mappings = List.copyOf(mappings);
    }
}
