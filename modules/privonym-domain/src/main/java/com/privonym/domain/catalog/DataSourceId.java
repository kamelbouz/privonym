package com.privonym.domain.catalog;

public record DataSourceId(long value) {
    public DataSourceId {
        if (value <= 0) {
            throw new IllegalArgumentException("Data source id must be positive");
        }
    }
}
