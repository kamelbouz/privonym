package com.privonym.application.port.in;

import com.privonym.application.service.SqlInventoryPreview;

/** Parses metadata from SQL without executing it. */
public interface PreviewSqlInventoryUseCase {
    SqlInventoryPreview preview(String technology, String sql);
}
